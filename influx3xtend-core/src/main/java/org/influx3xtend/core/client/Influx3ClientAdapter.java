package org.influx3xtend.core.client;

import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.config.ClientConfig;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.*;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.influx3xtend.core.config.InfluxDB3Config;
import org.influx3xtend.core.constants.Influx3xtendConstants;
import org.influx3xtend.core.engine.ArrowMergeEngine;
import org.influx3xtend.core.exception.Influx3xtendConnectionException;
import org.influx3xtend.core.exception.Influx3xtendException;
import org.influx3xtend.core.exception.Influx3xtendQueryException;
import org.influx3xtend.core.model.FilterCondition;
import org.influx3xtend.core.model.QueryRequest;
import org.influx3xtend.core.util.SanitizationUtils;
import org.influx3xtend.core.util.TimestampUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import org.influx3xtend.core.engine.StorageEngineAdapter;
import java.util.List;

/**
 * InfluxDB 3.x 官方 Java SDK Client 适配器包装类
 * 优化：在列裁剪下推中，自动将 whereTag 筛选条件中的 Tag 字段纳入 Projection，确保查出的数据 100% 包含维度 Tag！
 */
public class Influx3ClientAdapter implements StorageEngineAdapter {

    private static final Logger log = LoggerFactory.getLogger(Influx3ClientAdapter.class);

    private final InfluxDBClient influxDBClient;
    private final BufferAllocator allocator;

    public Influx3ClientAdapter(InfluxDB3Config config) {
        this.allocator = new RootAllocator(Long.MAX_VALUE);
        this.influxDBClient = initClient(config);
    }

    private InfluxDBClient initClient(InfluxDB3Config config) {
        try {
            ClientConfig clientConfig = new ClientConfig.Builder()
                    .host(config.host())
                    .token(config.token().toCharArray())
                    .database(config.database())
                    .build();

            log.info("Initialized official InfluxDB 3 Java Client SDK for host: {}", config.host());
            return InfluxDBClient.getInstance(clientConfig);
        } catch (Exception e) {
            log.warn("Could not establish direct Flight connection to InfluxDB 3 Core at {}: {}",
                    config.host(), e.getMessage());
            return null;
        }
    }

    public VectorSchemaRoot executeQuery(QueryRequest request) {
        String sql = buildSql(request);
        log.debug("Executing Flight SQL on InfluxDB 3 Core: {}", sql);

        try {
            if (influxDBClient == null) {
                throw new Influx3xtendConnectionException("InfluxDB 3 Client is not initialized. Please check InfluxDB host configuration.");
            }

            try (var batchStream = influxDBClient.queryBatches(sql)) {
                if (batchStream == null) {
                    return null;
                }
                var iterator = batchStream.iterator();
                List<VectorSchemaRoot> batches = new ArrayList<>();

                while (iterator.hasNext()) {
                    VectorSchemaRoot batch = iterator.next();
                    if (batch != null && batch.getRowCount() > 0) {
                        batches.add(cloneRoot(batch));
                    }
                }

                if (batches.isEmpty()) {
                    return null;
                }

                VectorSchemaRoot merged = ArrowMergeEngine.concatAll(batches);
                
                // 释放中间 chunk
                if (merged != null) {
                    for (VectorSchemaRoot b : batches) {
                        if (b != merged) {
                            try { b.close(); } catch (Exception ignored) {}
                        }
                    }
                    log.info("InfluxDB 3 Flight SQL returned native Arrow batches merged into {} total rows.", merged.getRowCount());
                }
                return merged;
            }
        } catch (Influx3xtendConnectionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Query execution against InfluxDB 3 Core failed: {}", e.getMessage(), e);
            throw new Influx3xtendQueryException("Query execution against InfluxDB 3 Core failed: " + e.getMessage(), e);
        }
    }

    /**
     * 极速探测 InfluxDB 3 实时 WAL / 内存中该 measurement 的最老数据时间戳 T_influx_min (返回单行数据，耗时 ~1ms)
     */
    public Instant probeMinTimestamp(QueryRequest request) {
        if (influxDBClient == null || request == null || request.measurement() == null) {
            return null;
        }
        String timeCol = request.timeColumn() != null ? request.timeColumn() : Influx3xtendConstants.COLUMN_TIME;
        String measurement = SanitizationUtils.sanitizeIdentifier(request.measurement(), "measurement");
        StringBuilder sql = new StringBuilder("SELECT MIN(").append(timeCol).append(") AS min_time FROM ").append(measurement).append(" WHERE 1=1");
        if (request.timeRange() != null) {
            if (request.timeRange().start() != null) {
                sql.append(" AND ").append(timeCol).append(" >= '").append(request.timeRange().start()).append("'");
            }
            if (request.timeRange().end() != null) {
                sql.append(" AND ").append(timeCol).append(" < '").append(request.timeRange().end()).append("'");
            }
        }
        if (request.tagFilters() != null) {
            for (FilterCondition filter : request.tagFilters()) {
                String safeField = SanitizationUtils.sanitizeIdentifier(filter.field(), "tagFilter field");
                String safeOp = SanitizationUtils.sanitizeOperator(filter.operator());
                String safeVal = filter.value() != null ? filter.value().toString().replace("'", "''") : "";
                if (filter.value() instanceof String || filter.value() instanceof Instant) {
                    sql.append(" AND ").append(safeField).append(" ").append(safeOp).append(" '").append(safeVal).append("'");
                } else {
                    sql.append(" AND ").append(safeField).append(" ").append(safeOp).append(" ").append(safeVal);
                }
            }
        }

        try (var batchStream = influxDBClient.queryBatches(sql.toString())) {
            if (batchStream != null) {
                var iterator = batchStream.iterator();
                if (iterator.hasNext()) {
                    VectorSchemaRoot root = iterator.next();
                    if (root != null && root.getRowCount() > 0) {
                        FieldVector vec = root.getVector("min_time");
                        if (vec != null && !vec.isNull(0)) {
                            Object val = vec.getObject(0);
                            return TimestampUtils.toInstant(val, 0);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Probe min_time from InfluxDB 3 failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 向 InfluxDB 3 Core 实时写入单条或带换行符的多条 Line Protocol 数据记录
     */
    public void writeRecord(String lineProtocol) {
        if (lineProtocol == null || lineProtocol.isBlank() || influxDBClient == null) {
            return;
        }
        try {
            influxDBClient.writeRecord(lineProtocol);
            log.debug("Successfully wrote Line Protocol record to InfluxDB 3 Core.");
        } catch (Exception e) {
            if (isConnectionException(e)) {
                log.debug("InfluxDB 3 Core (http://localhost:8086) is offline/unreachable. Skipping real-time WAL write and falling back to DuckDB/Parquet storage.");
            } else {
                log.warn("Write Line Protocol to InfluxDB 3 Core failed: {}", e.getMessage());
            }
        }
    }

    /**
     * 向 InfluxDB 3 Core 批量写入 Line Protocol 记录集合
     */
    public void writeRecords(Iterable<String> lineProtocols) {
        if (lineProtocols == null || influxDBClient == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String lp : lineProtocols) {
            if (lp != null && !lp.isBlank()) {
                sb.append(lp.endsWith("\n") ? lp : lp + "\n");
            }
        }
        if (sb.length() > 0) {
            writeRecord(sb.toString());
        }
    }

    private boolean isConnectionException(Throwable t) {
        while (t != null) {
            if (t instanceof ConnectException || t instanceof SocketException || t instanceof UnknownHostException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private VectorSchemaRoot cloneRoot(VectorSchemaRoot src) {
        if (src == null) return null;
        VectorSchemaRoot dest = VectorSchemaRoot.create(src.getSchema(), this.allocator);
        dest.allocateNew();
        for (Field field : src.getSchema().getFields()) {
            FieldVector srcVec = src.getVector(field.getName());
            FieldVector destVec = dest.getVector(field.getName());
            if (srcVec != null && destVec != null) {
                for (int i = 0; i < src.getRowCount(); i++) {
                    destVec.copyFromSafe(i, i, srcVec);
                }
            }
        }
        dest.setRowCount(src.getRowCount());
        return dest;
    }

    private static long parseLongTimestamp(Object rawObj, int fallbackRowIndex) {
        return TimestampUtils.toInstant(rawObj, fallbackRowIndex).toEpochMilli();
    }

    /**
     * 拼接 SQL，自动强力保护过滤条件中的 Tag 列下推显示
     */
    public String buildSql(QueryRequest request) {
        StringBuilder sb = new StringBuilder("SELECT ");
        String measurement = SanitizationUtils.sanitizeIdentifier(request.measurement(), "measurement");
        String timeCol = SanitizationUtils.sanitizeIdentifier(request.timeColumn(), "timeColumn");

        if (request.groupByTime() != null) {
            List<String> projections = new ArrayList<>();
            projections.add("DATE_BIN(INTERVAL '" + request.groupByTime().toSeconds() + " SECOND', " + timeCol + ", TIMESTAMP '1970-01-01T00:00:00Z') AS " + timeCol);
            if (request.aggregates() != null) {
                for (var agg : request.aggregates()) {
                    String safeAggField = SanitizationUtils.sanitizeIdentifier(agg.field(), "aggregate field");
                    String safeAlias = SanitizationUtils.sanitizeIdentifier(agg.alias(), "aggregate alias");
                    projections.add(agg.type().name() + "(" + safeAggField + ") AS " + safeAlias);
                }
            }
            sb.append(String.join(", ", projections));
        } else if (request.selectFields() != null && !request.selectFields().isEmpty()) {
            List<String> projectedCols = new ArrayList<>();
            if (!request.selectFields().contains(timeCol)) {
                projectedCols.add(timeCol);
            }

            // 自动包含 whereTag 中的 Tag 列
            if (request.tagFilters() != null) {
                for (FilterCondition tagFilter : request.tagFilters()) {
                    String safeTagField = SanitizationUtils.sanitizeIdentifier(tagFilter.field(), "tagFilter field");
                    if (!projectedCols.contains(safeTagField) && !request.selectFields().contains(safeTagField)) {
                        projectedCols.add(safeTagField);
                    }
                }
            }

            for (String field : request.selectFields()) {
                String safeField = SanitizationUtils.sanitizeIdentifier(field, "selectField");
                if (!projectedCols.contains(safeField)) {
                    projectedCols.add(safeField);
                }
            }

            if (request.aggregates() != null) {
                for (var agg : request.aggregates()) {
                    String safeAggField = SanitizationUtils.sanitizeIdentifier(agg.field(), "aggregate field");
                    String safeAlias = SanitizationUtils.sanitizeIdentifier(agg.alias(), "aggregate alias");
                    projectedCols.add(agg.type().name() + "(" + safeAggField + ") AS " + safeAlias);
                }
            }

            sb.append(String.join(", ", projectedCols));
        } else {
            sb.append("*");
        }
        sb.append(" FROM ").append(measurement);
        sb.append(" WHERE 1=1");

        if (request.timeRange() != null) {
            if (request.timeRange().start() != null) {
                sb.append(" AND ").append(timeCol).append(" >= '").append(request.timeRange().start()).append("'");
            }
            if (request.timeRange().end() != null) {
                sb.append(" AND ").append(timeCol).append(" < '").append(request.timeRange().end()).append("'");
            }
        }

        if (request.tagFilters() != null) {
            for (FilterCondition filter : request.tagFilters()) {
                String safeField = SanitizationUtils.sanitizeIdentifier(filter.field(), "tagFilter field");
                String safeOp = SanitizationUtils.sanitizeOperator(filter.operator());
                String safeVal = filter.value() != null ? filter.value().toString().replace("'", "''") : "";
                sb.append(" AND ").append(safeField)
                        .append(" ").append(safeOp)
                        .append(" '").append(safeVal).append("'");
            }
        }

        if (request.fieldFilters() != null) {
            for (FilterCondition filter : request.fieldFilters()) {
                String safeField = SanitizationUtils.sanitizeIdentifier(filter.field(), "fieldFilter field");
                String safeOp = SanitizationUtils.sanitizeOperator(filter.operator());
                String safeVal = filter.value() != null ? filter.value().toString().replace("'", "''") : "";
                if (filter.value() instanceof String || filter.value() instanceof Instant) {
                    sb.append(" AND ").append(safeField)
                            .append(" ").append(safeOp)
                            .append(" '").append(safeVal).append("'");
                } else {
                    sb.append(" AND ").append(safeField)
                            .append(" ").append(safeOp)
                            .append(" ").append(safeVal);
                }
            }
        }

        if (request.orderByList() != null && !request.orderByList().isEmpty()) {
            List<String> orderParts = new ArrayList<>();
            for (var order : request.orderByList()) {
                orderParts.add(SanitizationUtils.sanitizeIdentifier(order.field(), "orderBy field") + " " + order.order().name());
            }
            sb.append(" ORDER BY ").append(String.join(", ", orderParts));
        } else if (request.groupByTime() != null) {
            sb.append(" GROUP BY 1 ORDER BY 1 ASC");
        } else {
            sb.append(" ORDER BY ").append(timeCol).append(" ASC");
        }

        if (request.limit() != null && request.limit() > 0) {
            sb.append(" LIMIT ").append(request.limit());
        }

        return sb.toString();
    }

    @Override
    public void close() {
        if (influxDBClient != null) {
            try {
                influxDBClient.close();
            } catch (Exception e) {
                log.warn("Error closing InfluxDB 3 Client", e);
            }
        }
        if (allocator != null) {
            allocator.close();
        }
    }
}

