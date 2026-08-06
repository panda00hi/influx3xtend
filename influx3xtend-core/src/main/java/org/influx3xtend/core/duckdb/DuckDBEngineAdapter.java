package org.influx3xtend.core.duckdb;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.*;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.influx3xtend.core.config.S3StorageConfig;
import org.influx3xtend.core.config.StorageConfig;
import org.influx3xtend.core.constants.Influx3xtendConstants;
import org.influx3xtend.core.constants.Influx3xtendDefaults;
import org.influx3xtend.core.engine.StorageEngineAdapter;
import org.influx3xtend.core.exception.Influx3xtendException;
import org.influx3xtend.core.model.FilterCondition;
import org.influx3xtend.core.model.QueryRequest;
import org.influx3xtend.core.storage.ObjectStorageScanner;
import org.influx3xtend.core.util.SanitizationUtils;
import org.influx3xtend.core.util.TimestampUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 嵌入式 DuckDB JNI 引擎适配器 (DuckDBEngineAdapter)
 * 支持列裁剪下推 (Column Projection Pushdown)，自动在投影列表中合并过滤 Tag 字段
 * 原生支持本地磁盘 (Local Parquet) 与 S3 对象存储
 */
public class DuckDBEngineAdapter implements StorageEngineAdapter {

    private static final Logger log = LoggerFactory.getLogger(DuckDBEngineAdapter.class);

    private final StorageConfig storageConfig;
    private final String maxMemory;
    private Connection connection;
    private final BufferAllocator allocator;
    private final ObjectStorageScanner scanner;

    public Connection getConnection() {
        return connection;
    }

    public Connection getRawConnection() {
        return connection;
    }

    public DuckDBEngineAdapter(StorageConfig storageConfig, String maxMemory) {
        this.storageConfig = storageConfig;
        this.maxMemory = Influx3xtendDefaults.getDuckdbMaxMemory(maxMemory);
        this.allocator = new RootAllocator(Long.MAX_VALUE);
        this.scanner = new ObjectStorageScanner(storageConfig);
        initDuckDB();
    }

    private void initDuckDB() {
        try {
            Class.forName("org.duckdb.DuckDBDriver");
            this.connection = DriverManager.getConnection("jdbc:duckdb:");
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET max_memory = '" + maxMemory + "'");
                stmt.execute("INSTALL parquet; LOAD parquet;");

                if (storageConfig instanceof S3StorageConfig s3Config && s3Config.bucket() != null) {
                    try {
                        stmt.execute("INSTALL httpfs; LOAD httpfs;");
                        if (s3Config.accessKey() != null) {
                            stmt.execute("SET s3_access_key_id='" + s3Config.accessKey() + "'");
                            stmt.execute("SET s3_secret_access_key='" + s3Config.secretKey() + "'");
                            stmt.execute("SET s3_region='" + (s3Config.region() != null ? s3Config.region() : Influx3xtendConstants.DEFAULT_S3_REGION) + "'");
                            if (s3Config.endpoint() != null) {
                                stmt.execute("SET s3_endpoint='" + s3Config.endpoint().replace("https://", "").replace("http://", "") + "'");
                            }
                        }
                    } catch (Exception e) {
                        log.warn("httpfs extension load ignored: {}", e.getMessage());
                    }
                }
            }
            log.debug("Initialized Embedded DuckDB engine with max_memory={}", maxMemory);
        } catch (Exception e) {
            log.error("Failed to initialize DuckDB JNI engine", e);
            throw new Influx3xtendException("Failed to initialize DuckDB JNI engine: " + e.getMessage(), e);
        }
    }

    @Override
    public VectorSchemaRoot executeQuery(QueryRequest request) {
        return queryParquet(request);
    }

    /**
     * 极速查询存储中的 Parquet 文件，构建 Column-Projected VectorSchemaRoot
     */
    public VectorSchemaRoot queryParquet(QueryRequest request) {
        List<String> files = scanner.scanParquetFiles(request.measurement(), request.timeRange());
        if (files == null || files.isEmpty()) {
            log.info("No Parquet files found for measurement [{}], returning empty historical slice.", request.measurement());
            return null;
        }

        String sql = buildParquetSql(request, files);
        log.debug("Executing DuckDB Parquet Column-Projected SQL: {}", sql);

        VectorSchemaRoot root = null;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            List<Field> fields = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                String colName = metaData.getColumnLabel(i);
                int sqlType = metaData.getColumnType(i);

                if (request.timeColumn().equalsIgnoreCase(colName)) {
                    fields.add(Field.nullable(request.timeColumn(), new ArrowType.Timestamp(TimeUnit.MILLISECOND, null)));
                } else if (sqlType == Types.DOUBLE || sqlType == Types.FLOAT || sqlType == Types.REAL || sqlType == Types.DECIMAL) {
                    fields.add(Field.nullable(colName, new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)));
                } else if (sqlType == Types.BIGINT || sqlType == Types.INTEGER || sqlType == Types.SMALLINT || sqlType == Types.TINYINT) {
                    fields.add(Field.nullable(colName, new ArrowType.Int(64, true)));
                } else {
                    fields.add(Field.nullable(colName, new ArrowType.Utf8()));
                }
            }

            Schema schema = new Schema(fields);
            root = VectorSchemaRoot.create(schema, allocator);
            root.allocateNew();

            int rowIdx = 0;
            while (rs.next()) {
                for (int i = 1; i <= columnCount; i++) {
                    String colName = metaData.getColumnLabel(i);
                    FieldVector vec = root.getVector(colName);
                    Object val = rs.getObject(i);

                    if (val == null) {
                        if (vec != null) {
                            vec.setNull(rowIdx);
                        }
                        continue;
                    }

                    if (request.timeColumn().equalsIgnoreCase(colName)) {
                        long epochMs = TimestampUtils.toInstant(val, rowIdx).toEpochMilli();
                        if (vec instanceof TimeStampMilliVector tsMilli) {
                            tsMilli.setSafe(rowIdx, epochMs);
                        } else if (vec instanceof TimeStampVector tsVec) {
                            tsVec.setSafe(rowIdx, epochMs);
                        } else if (vec instanceof BigIntVector bigInt) {
                            bigInt.setSafe(rowIdx, epochMs);
                        }
                    } else if (vec instanceof Float8Vector float8) {
                        if (val instanceof Number num) {
                            float8.setSafe(rowIdx, num.doubleValue());
                        }
                    } else if (vec instanceof BigIntVector bigInt) {
                        if (val instanceof Number num) {
                            bigInt.setSafe(rowIdx, num.longValue());
                        }
                    } else if (vec instanceof VarCharVector strVec) {
                        strVec.setSafe(rowIdx, val.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
                rowIdx++;
            }

            root.setRowCount(rowIdx);
            log.info("DuckDB Parquet query returned {} rows.", rowIdx);
            if (rowIdx > 0) {
                return root;
            } else {
                root.close();
                return null;
            }
        } catch (Exception e) {
            log.error("DuckDB Parquet query execution failed: {}", e.getMessage(), e);
            if (root != null) {
                try {
                    root.close();
                } catch (Exception ignored) {}
            }
            throw new Influx3xtendException("DuckDB Parquet query execution failed: " + e.getMessage(), e);
        }
    }

    /**
     * 支持列裁剪下推 (Column Projection Pushdown)
     * 当 selectFields 存在时，自动合并必须的时间轴 timeColumn、whereTag 中的 Tag 列以及指定的 Field 列
     */
    public String buildParquetSql(QueryRequest request) {
        List<String> files = scanner.scanParquetFiles(request.measurement(), request.timeRange());
        return buildParquetSql(request, files);
    }

    public String buildParquetSql(QueryRequest request, List<String> files) {
        StringBuilder sql = new StringBuilder("SELECT ");
        String timeCol = SanitizationUtils.sanitizeIdentifier(request.timeColumn(), "timeColumn");

        if (request.groupByTime() != null) {
            List<String> projections = new ArrayList<>();
            projections.add("time_bucket(INTERVAL '" + request.groupByTime().toSeconds() + " SECONDS', " + timeCol + ") AS " + timeCol);
            for (var agg : request.aggregates()) {
                String safeAggField = SanitizationUtils.sanitizeIdentifier(agg.field(), "aggregate field");
                String safeAlias = SanitizationUtils.sanitizeIdentifier(agg.alias(), "aggregate alias");
                projections.add(agg.type().name() + "(" + safeAggField + ") AS " + safeAlias);
            }
            sql.append(String.join(", ", projections));
        } else if (request.selectFields() != null && !request.selectFields().isEmpty()) {
            List<String> projections = new ArrayList<>();
            // 必须保底包含时间轴
            if (!request.selectFields().contains(timeCol)) {
                projections.add(timeCol);
            }
            // 自动包含 whereTag 中的 Tag 维度列
            if (request.tagFilters() != null) {
                for (FilterCondition tagFilter : request.tagFilters()) {
                    String safeTagField = SanitizationUtils.sanitizeIdentifier(tagFilter.field(), "tagFilter field");
                    if (!projections.contains(safeTagField) && !request.selectFields().contains(safeTagField)) {
                        projections.add(safeTagField);
                    }
                }
            }

            for (String field : request.selectFields()) {
                String safeField = SanitizationUtils.sanitizeIdentifier(field, "selectField");
                if (!projections.contains(safeField)) {
                    projections.add(safeField);
                }
            }

            for (var agg : request.aggregates()) {
                String safeAggField = SanitizationUtils.sanitizeIdentifier(agg.field(), "aggregate field");
                String safeAlias = SanitizationUtils.sanitizeIdentifier(agg.alias(), "aggregate alias");
                projections.add(agg.type().name() + "(" + safeAggField + ") AS " + safeAlias);
            }
            sql.append(String.join(", ", projections));
        } else {
            sql.append("*");
        }

        sql.append(" FROM read_parquet([");
        for (int i = 0; i < files.size(); i++) {
            sql.append("'").append(files.get(i).replace("'", "''")).append("'");
            if (i < files.size() - 1) {
                sql.append(", ");
            }
        }
        sql.append("], union_by_name=true)");
        String startIso = request.timeRange().start().toString();
        String endIso = request.timeRange().end().toString();
        long startMs = request.timeRange().start().toEpochMilli();
        long endMs = request.timeRange().end().toEpochMilli();

        sql.append(" WHERE ((TRY_CAST(").append(timeCol).append(" AS TIMESTAMPTZ) >= TIMESTAMPTZ '").append(startIso).append("' AND TRY_CAST(")
           .append(timeCol).append(" AS TIMESTAMPTZ) < TIMESTAMPTZ '").append(endIso).append("') OR (")
           .append(timeCol).append(" >= epoch_ms(").append(startMs).append(") AND ").append(timeCol).append(" < epoch_ms(").append(endMs).append(")))");

        if (request.tagFilters() != null) {
            for (var tag : request.tagFilters()) {
                String safeTagField = SanitizationUtils.sanitizeIdentifier(tag.field(), "tagFilter field");
                String safeTagOp = SanitizationUtils.sanitizeOperator(tag.operator());
                String safeVal = tag.value() != null ? tag.value().toString().replace("'", "''") : "";
                sql.append(" AND ").append(safeTagField).append(" ").append(safeTagOp).append(" '").append(safeVal).append("'");
            }
        }

        if (request.fieldFilters() != null) {
            for (var field : request.fieldFilters()) {
                String safeField = SanitizationUtils.sanitizeIdentifier(field.field(), "fieldFilter field");
                String safeFieldOp = SanitizationUtils.sanitizeOperator(field.operator());
                String safeVal = field.value() != null ? field.value().toString().replace("'", "''") : "";
                if (field.value() instanceof String || field.value() instanceof Instant) {
                    sql.append(" AND ").append(safeField).append(" ").append(safeFieldOp).append(" '").append(safeVal).append("'");
                } else {
                    sql.append(" AND ").append(safeField).append(" ").append(safeFieldOp).append(" ").append(safeVal);
                }
            }
        }

        if (request.orderByList() != null && !request.orderByList().isEmpty()) {
            List<String> orderParts = new ArrayList<>();
            for (var order : request.orderByList()) {
                orderParts.add(SanitizationUtils.sanitizeIdentifier(order.field(), "orderBy field") + " " + order.order().name());
            }
            sql.append(" ORDER BY ").append(String.join(", ", orderParts));
        } else if (request.groupByTime() != null) {
            sql.append(" GROUP BY 1 ORDER BY 1 ASC");
        } else {
            sql.append(" ORDER BY ").append(timeCol).append(" ASC");
        }

        if (request.limit() != null && request.limit() > 0) {
            sql.append(" LIMIT ").append(request.limit());
        }

        return sql.toString();
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (Exception e) {
            log.warn("Error closing DuckDB Connection", e);
        }
        if (scanner != null) {
            try {
                scanner.close();
            } catch (Exception e) {
                log.warn("Error closing ObjectStorageScanner", e);
            }
        }
        if (allocator != null) {
            try {
                allocator.close();
            } catch (Exception e) {
                log.warn("Error closing BufferAllocator", e);
            }
        }
    }
}
