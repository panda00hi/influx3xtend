package org.influx3xtend.core.router;

import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.influx3xtend.core.constants.Influx3xtendConstants;
import org.influx3xtend.core.constants.Influx3xtendDefaults;
import org.influx3xtend.core.engine.ArrowMergeEngine;
import org.influx3xtend.core.engine.StorageEngineAdapter;
import org.influx3xtend.core.model.QueryRequest;
import org.influx3xtend.core.model.QueryResult;
import org.influx3xtend.core.model.SortOrder;
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
 * 1. 动态探针 (Probe)：向 InfluxDB 3 Core 探针查询当前内存/WAL/落盘数据真正包含的最老时间戳 T_influx_min (~1ms)。
 * 2. 排序感知调度 (Order-Aware Priority Dispatching)：
 *    - 当 ORDER BY time DESC 时：优先检索 InfluxDB 3 [T_influx_min, reqEnd)，满足 limit 则跳过 DuckDB；未满足则由 DuckDB 查补 [reqStart, T_influx_min)；
 *    - 当 ORDER BY time ASC 时：优先检索 DuckDB 冷库 [reqStart, T_influx_min)，满足 limit 则跳过 InfluxDB 3；未满足则由 InfluxDB 3 查补 [T_influx_min, reqEnd)。
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
        Integer limit = request.limit();

        boolean isDesc = request.orderByList() != null
                && !request.orderByList().isEmpty()
                && request.orderByList().get(0).order() == SortOrder.DESC;

        // 1. 极速探针查询 InfluxDB 3 Core 中最老点位 T_influx_min (~1ms)
        Instant minInRealtime = null;
        try {
            minInRealtime = influx3Adapter.probeMinTimestamp(request);
        } catch (Exception e) {
            log.debug("Probe min_time from InfluxDB 3 failed: {}", e.getMessage());
        }

        // 边界保护 1: 若探针到的最老点位 T_influx_min >= reqEnd (并发微秒/毫秒舍入漂移或超范围)，视作热库在该请求区间无数据
        if (minInRealtime != null && !minInRealtime.isBefore(reqEnd)) {
            minInRealtime = null;
        }

        VectorSchemaRoot realtimeRoot = null;
        VectorSchemaRoot historicalRoot = null;
        VectorSchemaRoot mergedRoot = null;

        try {
            // 场景 A: InfluxDB 3 中完全无符合条件的数据（探针返回 null）
            if (minInRealtime == null) {
                // 尝试优先向 InfluxDB 3 发起全量查询
                try {
                    realtimeRoot = influx3Adapter.executeQuery(request);
                } catch (Exception e) {
                    log.warn("InfluxDB 3 query failed, falling back to DuckDB Parquet storage: {}", e.getMessage());
                }

                int realtimeRows = realtimeRoot != null ? realtimeRoot.getRowCount() : 0;
                boolean limitSatisfied = (limit != null && limit > 0 && realtimeRows >= limit);

                if (!limitSatisfied) {
                    Integer remainingLimit = (limit != null && limit > 0) ? Math.max(1, limit - realtimeRows) : null;
                    log.info("InfluxDB 3 returned {} rows, routing cold query [{} ~ {}) to DuckDB (remainingLimit={})",
                            realtimeRows, reqStart, reqEnd, remainingLimit);
                    historicalRoot = duckDBAdapter.executeQuery(request.withLimit(remainingLimit));
                }

                int historicalRows = historicalRoot != null ? historicalRoot.getRowCount() : 0;
                mergedRoot = ArrowMergeEngine.merge(historicalRoot, realtimeRoot, request);
                long elapsedTime = System.currentTimeMillis() - startTime;
                return new QueryResult(mergedRoot, elapsedTime, realtimeRows, historicalRows);
            }

            // 场景 B: InfluxDB 3 Core 中的最老点位已经完全覆盖了请求范围 (minInRealtime <= reqStart)
            if (!reqStart.isBefore(minInRealtime)) {
                log.debug("InfluxDB 3 Core fully covers range [{} ~ {}] (T_influx_min={}). Direct Flight query.",
                        reqStart, reqEnd, minInRealtime);
                realtimeRoot = influx3Adapter.executeQuery(request);
                int realtimeRows = realtimeRoot != null ? realtimeRoot.getRowCount() : 0;
                long elapsedTime = System.currentTimeMillis() - startTime;
                return new QueryResult(realtimeRoot, elapsedTime, realtimeRows, 0);
            }

            // 场景 C: 冷热混合切片 [reqStart, minInRealtime) & [minInRealtime, reqEnd)
            Instant sliceCutoff = minInRealtime;

            // 边界保护 2: 确保 sliceCutoff 严格落在 (reqStart, reqEnd) 开区间内
            if (!sliceCutoff.isAfter(reqStart) || !sliceCutoff.isBefore(reqEnd)) {
                realtimeRoot = influx3Adapter.executeQuery(request);
                int realtimeRows = realtimeRoot != null ? realtimeRoot.getRowCount() : 0;
                long elapsedTime = System.currentTimeMillis() - startTime;
                return new QueryResult(realtimeRoot, elapsedTime, realtimeRows, 0);
            }

            if (isDesc) {
                // 倒序场景 ORDER BY DESC：优先查 InfluxDB 3 最新切片 [sliceCutoff, reqEnd)
                QueryRequest realtimeReq = request.withTimeRange(TimeRange.of(sliceCutoff, reqEnd));
                try {
                    realtimeRoot = influx3Adapter.executeQuery(realtimeReq);
                } catch (Exception e) {
                    log.warn("Realtime InfluxDB 3 query failed ({}), falling back to DuckDB", e.getMessage());
                }

                int realtimeRows = realtimeRoot != null ? realtimeRoot.getRowCount() : 0;
                boolean limitSatisfied = (limit != null && limit > 0 && realtimeRows >= limit);

                if (!limitSatisfied && reqStart.isBefore(sliceCutoff)) {
                    Integer remainingLimit = (limit != null && limit > 0) ? Math.max(1, limit - realtimeRows) : null;
                    QueryRequest historicalReq = request.withTimeRange(TimeRange.of(reqStart, sliceCutoff)).withLimit(remainingLimit);
                    log.info("Routing historical cold slice [{} ~ {}) to DuckDB (remainingLimit={})",
                            reqStart, sliceCutoff, remainingLimit);
                    historicalRoot = duckDBAdapter.executeQuery(historicalReq);
                }
            } else {
                // 正序场景 ORDER BY ASC：优先查 DuckDB 最早冷切片 [reqStart, sliceCutoff)
                QueryRequest historicalReq = request.withTimeRange(TimeRange.of(reqStart, sliceCutoff));
                try {
                    historicalRoot = duckDBAdapter.executeQuery(historicalReq);
                } catch (Exception e) {
                    log.warn("DuckDB historical Parquet query failed ({}), continuing to InfluxDB 3", e.getMessage());
                }

                int historicalRows = historicalRoot != null ? historicalRoot.getRowCount() : 0;
                boolean limitSatisfied = (limit != null && limit > 0 && historicalRows >= limit);

                if (!limitSatisfied && sliceCutoff.isBefore(reqEnd)) {
                    Integer remainingLimit = (limit != null && limit > 0) ? Math.max(1, limit - historicalRows) : null;
                    QueryRequest realtimeReq = request.withTimeRange(TimeRange.of(sliceCutoff, reqEnd)).withLimit(remainingLimit);
                    log.info("Routing realtime slice [{} ~ {}) to InfluxDB 3 (remainingLimit={})",
                            sliceCutoff, reqEnd, remainingLimit);
                    try {
                        realtimeRoot = influx3Adapter.executeQuery(realtimeReq);
                    } catch (Exception e) {
                        log.warn("Realtime InfluxDB 3 query failed ({})", e.getMessage());
                    }
                }
            }

            int realtimeRows = realtimeRoot != null ? realtimeRoot.getRowCount() : 0;
            int historicalRows = historicalRoot != null ? historicalRoot.getRowCount() : 0;

            mergedRoot = ArrowMergeEngine.merge(historicalRoot, realtimeRoot, request);
            long elapsedTime = System.currentTimeMillis() - startTime;
            return new QueryResult(mergedRoot, elapsedTime, realtimeRows, historicalRows);

        } finally {
            if (realtimeRoot != null && realtimeRoot != mergedRoot) {
                try { realtimeRoot.close(); } catch (Exception ignored) {}
            }
            if (historicalRoot != null && historicalRoot != mergedRoot) {
                try { historicalRoot.close(); } catch (Exception ignored) {}
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
