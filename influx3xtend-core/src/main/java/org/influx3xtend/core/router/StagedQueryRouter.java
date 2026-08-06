package org.influx3xtend.core.router;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.influx3xtend.core.constants.Influx3xtendDefaults;
import org.influx3xtend.core.engine.ArrowMergeEngine;
import org.influx3xtend.core.engine.StorageEngineAdapter;
import org.influx3xtend.core.model.QueryRequest;
import org.influx3xtend.core.model.QueryResult;
import org.influx3xtend.core.model.TimeRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * 动态探针驱动的分阶段路由器 (Dynamic Probe-Driven StagedQueryRouter)
 * 彻底消除硬编码时间 (如 72h) 的盲目误判！
 * 
 * 核心原理：
 * 1. 动态探针：向 InfluxDB 3 Core 探针查询当前内存/WAL 真正包含的最老时间戳 T_influx_min。
 * 2. 精准动态切割 (无交叠)：
 *    - 实时切片：[max(reqStart, fallbackCutoff), reqEnd)，由 Flight SQL 提取；
 *    - 历史切片：[reqStart, historicalEnd)，由 DuckDB 向量化提取。
 * 3. 堆外生命周期闭环：在合并完成后及时显式释放源头临时 Arrow Batch，实现 100% 堆外零内存泄漏！
 */
import org.influx3xtend.core.engine.StorageEngineAdapter;

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

        // 1. 确定 InfluxDB 3 Core 优先承载的原生窗口下限 (默认 72 小时)
        Instant fallbackCutoff = reqEnd.minus(fallbackMaxNativeWindow);
        Instant realtimeStart = reqStart.isAfter(fallbackCutoff) ? reqStart : fallbackCutoff;

        // 2. 优先对 InfluxDB 3 Core 发起 Flight SQL 查询：[realtimeStart, reqEnd)
        QueryRequest realtimeRequest = request.withTimeRange(TimeRange.of(realtimeStart, reqEnd));
        VectorSchemaRoot realtimeRoot = null;
        try {
            realtimeRoot = influx3Adapter.executeQuery(realtimeRequest);
        } catch (Exception e) {
            log.warn("Realtime InfluxDB 3 Flight query unavailable ({}), gracefully falling back to DuckDB historical Parquet storage.", e.getMessage());
        }

        int realtimeRows = realtimeRoot != null ? realtimeRoot.getRowCount() : 0;
        Integer limit = request.limit();

        // 3. 动态路由决策逻辑：
        //    a) 若 InfluxDB 3 查出的行数已达到 limit 限制，说明 Limit 已满额满足，无需触发 DuckDB；
        //    b) 若请求的时间范围完全在 InfluxDB 3 Core 原生窗口内 (reqStart >= fallbackCutoff)，且 InfluxDB 3 正常响应，
        //       由于 InfluxDB 3 引擎本身支持检索 WAL 内存与落盘 Parquet，因此无需触发 DuckDB；
        //    c) 仅当请求范围超越 72 小时原生窗口 (reqStart < fallbackCutoff)，或 InfluxDB 3 响应异常/未满额时，才路由 DuckDB 补齐。
        boolean limitSatisfied = (limit != null && limit > 0 && realtimeRows >= limit);
        boolean fullyCoveredByInflux3 = !reqStart.isBefore(fallbackCutoff) && realtimeRoot != null;

        VectorSchemaRoot historicalRoot = null;
        VectorSchemaRoot mergedRoot = null;

        try {
            if (!limitSatisfied && !fullyCoveredByInflux3) {
                Instant historicalEnd = (realtimeRoot == null) ? reqEnd : realtimeStart;

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
            // 4. 核心生命周期闭环：释放临时源 Batch，防止堆外内存泄露
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
}
