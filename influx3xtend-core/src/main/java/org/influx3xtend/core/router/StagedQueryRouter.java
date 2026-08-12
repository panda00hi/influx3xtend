package org.influx3xtend.core.router;

import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.influx3xtend.core.constants.Influx3xtendConstants;
import org.influx3xtend.core.constants.Influx3xtendDefaults;
import org.influx3xtend.core.engine.ArrowMergeEngine;
import org.influx3xtend.core.engine.StorageEngineAdapter;
import org.influx3xtend.core.model.QueryRequest;
import org.influx3xtend.core.model.QueryResult;
import org.influx3xtend.core.model.TimeRange;
import org.influx3xtend.core.util.TimestampUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * 动态探针驱动的分阶段路由器 (Dynamic Probe-Driven StagedQueryRouter)
 * 彻底消除硬编码时间 (如 72h) 的盲目误判！
 * 
 * 核心原理：
 * 1. 动态探针：优先向 InfluxDB 3 Core 查询请求的全量时间范围 [reqStart, reqEnd)，并探查返回数据的最老点位时间戳 T_influx_min。
 * 2. 精准动态切割 (无交叠)：
 *    - 实时切片：[max(reqStart, T_influx_min), reqEnd)，由 InfluxDB 3 Core Flight SQL 提取；
 *    - 历史切片：[reqStart, min(T_influx_min, reqEnd))，由 DuckDB 向量化提取。
 * 3. 堆外生命周期闭环：在合并完成后及时显式释放源头临时 Arrow Batch，实现 100% 堆外零内存泄漏！
 */
public class StagedQueryRouter {

    private static final Logger log = LoggerFactory.getLogger(StagedQueryRouter.class);

    private final StorageEngineAdapter influx3Adapter;
    private final StorageEngineAdapter duckDBAdapter;
    private final Duration fallbackMaxNativeWindow;

    public StagedQueryRouter(StorageEngineAdapter influx3Adapter, StorageEngineAdapter duckDBAdapter) {
        this(influx3Adapter, duckDBAdapter, Influx3xtendDefaults.getMaxNativeQueryWindow(null));
    }

    public StagedQueryRouter(StorageEngineAdapter influx3Adapter, StorageEngineAdapter duckDBAdapter, Duration fallbackMaxNativeWindow) {
        this.influx3Adapter = influx3Adapter;
        this.duckDBAdapter = duckDBAdapter;
        this.fallbackMaxNativeWindow = Influx3xtendDefaults.getMaxNativeQueryWindow(fallbackMaxNativeWindow);
    }

    public QueryResult routeAndExecute(QueryRequest request) {
        long startTime = System.currentTimeMillis();

        Instant reqStart = request.timeRange().start();
        Instant reqEnd = request.timeRange().end();

        // 1. 优先对 InfluxDB 3 Core 发起 Flight SQL 查询：[reqStart, reqEnd)
        // InfluxDB 3 Core 作为主要存储引擎，完整存储了 WAL 内存与落盘 Parquet
        VectorSchemaRoot realtimeRoot = null;
        try {
            realtimeRoot = influx3Adapter.executeQuery(request);
        } catch (Exception e) {
            log.warn("Realtime InfluxDB 3 Flight query for full range [{} ~ {}) failed/unavailable ({}), trying fallback cutoff window.",
                    reqStart, reqEnd, e.getMessage());
            // 容灾兜底：若全量范围查询在 InfluxDB 3 产生异常，回退至原生 72h 降级窗口
            Instant fallbackCutoff = reqEnd.minus(fallbackMaxNativeWindow);
            if (reqStart.isBefore(fallbackCutoff)) {
                try {
                    realtimeRoot = influx3Adapter.executeQuery(request.withTimeRange(TimeRange.of(fallbackCutoff, reqEnd)));
                } catch (Exception fallbackEx) {
                    log.warn("Fallback InfluxDB 3 Flight query also failed: {}", fallbackEx.getMessage());
                }
            }
        }

        int realtimeRows = realtimeRoot != null ? realtimeRoot.getRowCount() : 0;
        Integer limit = request.limit();

        // 2. 动态探针分析 InfluxDB 3 返回数据中的最老时间点 T_influx_min
        Instant minInRealtime = findMinTimestamp(realtimeRoot, request.timeColumn());

        // 3. 动态路由决策逻辑：
        //    a) 若 InfluxDB 3 查出的行数已达到 limit 限制，说明 Limit 已满额满足，无需触发 DuckDB；
        //    b) 若 InfluxDB 3 返回的最老数据点 T_influx_min <= reqStart，说明 InfluxDB 3 已经完全覆盖了请求范围，无需触发 DuckDB；
        //    c) 仅当 InfluxDB 3 查出的行数未达到 limit，且 reqStart 早于 InfluxDB 3 最老点位时，才触发 DuckDB 查补 DuckDB Parquet 历史切片。
        boolean limitSatisfied = (limit != null && limit > 0 && realtimeRows >= limit);
        boolean fullyCoveredByInflux3 = (minInRealtime != null && !reqStart.isBefore(minInRealtime));

        VectorSchemaRoot historicalRoot = null;
        VectorSchemaRoot mergedRoot = null;

        try {
            if (!limitSatisfied && !fullyCoveredByInflux3) {
                Instant historicalEnd = (minInRealtime != null) ? minInRealtime : reqEnd;

                if (reqStart.isBefore(historicalEnd)) {
                    TimeRange historicalRange = TimeRange.of(reqStart, historicalEnd);
                    Integer remainingLimit = (limit != null && limit > 0) ? Math.max(1, limit - realtimeRows) : null;
                    QueryRequest historicalRequest = request.withTimeRange(historicalRange).withLimit(remainingLimit);

                    log.info("Routing historical cold slice [{} ~ {}) to DuckDB Parquet Engine (remainingLimit={})",
                            reqStart, historicalEnd, remainingLimit);
                    historicalRoot = duckDBAdapter.executeQuery(historicalRequest);
                }
            } else {
                log.debug("InfluxDB 3 Core fully satisfied query (realtimeRows={}, limitSatisfied={}, fullyCoveredByInflux3={}). Bypassing DuckDB.",
                        realtimeRows, limitSatisfied, fullyCoveredByInflux3);
            }

            int historicalRows = historicalRoot != null ? historicalRoot.getRowCount() : 0;

            // 4. 将 DuckDB Parquet 历史切片 (older) 与 InfluxDB 3 实时切片 (newer) 做堆外无缝序合并
            mergedRoot = ArrowMergeEngine.merge(historicalRoot, realtimeRoot, request);
            long elapsedTime = System.currentTimeMillis() - startTime;
            
            return new QueryResult(mergedRoot, elapsedTime, realtimeRows, historicalRows);
        } finally {
            // 5. 核心生命周期闭环：释放临时源 Batch，防止堆外内存泄露
            if (realtimeRoot != null && realtimeRoot != mergedRoot) {
                try {
                    realtimeRoot.close();
                } catch (Exception ignored) {}
            }
            if (historicalRoot != null && historicalRoot != mergedRoot) {
                try {
                    historicalRoot.close();
                } catch (Exception ignored) {}
            }
        }
    }

    private Instant findMinTimestamp(VectorSchemaRoot root, String timeColumn) {
        if (root == null || root.getRowCount() == 0) {
            return null;
        }
        String timeCol = timeColumn != null ? timeColumn : Influx3xtendConstants.COLUMN_TIME;
        FieldVector timeVector = root.getVector(timeCol);
        if (timeVector == null) {
            return null;
        }

        long minEpochMs = Long.MAX_VALUE;
        int rowCount = root.getRowCount();
        for (int i = 0; i < rowCount; i++) {
            if (!timeVector.isNull(i)) {
                Object val = timeVector.getObject(i);
                Instant inst = TimestampUtils.toInstant(val, i);
                if (inst != null && inst.toEpochMilli() < minEpochMs) {
                    minEpochMs = inst.toEpochMilli();
                }
            }
        }
        return minEpochMs != Long.MAX_VALUE ? Instant.ofEpochMilli(minEpochMs) : null;
    }
}
