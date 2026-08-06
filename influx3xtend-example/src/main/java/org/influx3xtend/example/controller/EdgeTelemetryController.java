package org.influx3xtend.example.controller;

import org.influx3xtend.core.api.Influx3xtendClient;
import org.influx3xtend.core.model.AggregateType;
import org.influx3xtend.core.model.QueryResult;
import org.influx3xtend.example.dto.AggregatedPointDto;
import org.influx3xtend.example.dto.HighFrequencyWaveformDto;
import org.influx3xtend.example.dto.RegularTelemetryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工业物联网历史数据与降采样 REST 控制器 (EdgeTelemetryController)
 * 封装并暴露 Influx3xtend 核心查询能力，供前端 Web 看板与报表分析使用
 */
@RestController
@RequestMapping("/api/edge/telemetry")
@CrossOrigin(origins = "*")
public class EdgeTelemetryController {

    private static final Logger log = LoggerFactory.getLogger(EdgeTelemetryController.class);

    @Autowired
    private Influx3xtendClient client;

    /**
     * 1. 明细历史数据查询端点
     * GET /api/edge/telemetry/query?deviceId=DEV_REG_001&measurement=regular_telemetry&minutes=15&limit=1000
     */
    @GetMapping("/query")
    public Map<String, Object> queryHistory(
            @RequestParam(name = "deviceId", defaultValue = "DEV_REG_001") String deviceId,
            @RequestParam(name = "measurement", defaultValue = "regular_telemetry") String measurement,
            @RequestParam(name = "minutes", defaultValue = "15") int minutes,
            @RequestParam(name = "startTime", required = false) String startTimeStr,
            @RequestParam(name = "endTime", required = false) String endTimeStr,
            @RequestParam(name = "limit", defaultValue = "1000") int limit,
            @RequestParam(name = "order", defaultValue = "ASC") String order) {

        Instant start;
        Instant end;
        if (startTimeStr != null && !startTimeStr.isBlank() && endTimeStr != null && !endTimeStr.isBlank()) {
            try {
                start = Instant.parse(startTimeStr);
                end = Instant.parse(endTimeStr);
            } catch (Exception e) {
                end = Instant.now();
                start = end.minus(Duration.ofMinutes(minutes));
            }
        } else {
            end = Instant.now();
            start = end.minus(Duration.ofMinutes(minutes));
        }

        log.info("REST API [/query]: deviceId=[{}], measurement=[{}], start=[{}], end=[{}], limit=[{}], order=[{}]",
                deviceId, measurement, start, end, limit, order);

        var queryBuilder = client.query()
                .measurement(measurement)
                .timeRange(start, end)
                .whereTag("device_id", deviceId)
                .limit(limit);

        if ("DESC".equalsIgnoreCase(order)) {
            queryBuilder.orderByTimeDesc();
        } else {
            queryBuilder.orderByTimeAsc();
        }

        try (QueryResult result = queryBuilder.execute()) {

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "success");
            response.put("deviceId", deviceId);
            response.put("measurement", measurement);
            response.put("totalRecords", result.getRowCount());
            response.put("executionTimeMs", result.getExecutionTimeMs());
            response.put("realtimeRowsCount", result.getRealtimeRows());
            response.put("historicalRowsCount", result.getHistoricalRows());

            if ("high_frequency_waveform".equalsIgnoreCase(measurement)) {
                response.put("data", result.toPojoList(HighFrequencyWaveformDto.class));
            } else {
                response.put("data", result.toPojoList(RegularTelemetryDto.class));
            }

            return response;
        }
    }

    /**
     * 2. 时间桶降采样聚合查询端点 (支持 AVG/MAX/MIN/COUNT)
     * GET /api/edge/telemetry/aggregate?measurement=regular_telemetry&field=temperature&bucketSeconds=5
     */
    @GetMapping("/aggregate")
    public Map<String, Object> getAggregatedTelemetry(
            @RequestParam(name = "measurement", defaultValue = "regular_telemetry") String measurement,
            @RequestParam(name = "field", defaultValue = "temperature") String field,
            @RequestParam(name = "bucketSeconds", defaultValue = "5") int bucketSeconds,
            @RequestParam(name = "minutes", defaultValue = "15") int minutes,
            @RequestParam(name = "startTime", required = false) String startTimeStr,
            @RequestParam(name = "endTime", required = false) String endTimeStr) {

        Instant start;
        Instant end;
        if (startTimeStr != null && !startTimeStr.isBlank() && endTimeStr != null && !endTimeStr.isBlank()) {
            try {
                start = Instant.parse(startTimeStr);
                end = Instant.parse(endTimeStr);
            } catch (Exception e) {
                end = Instant.now();
                start = end.minus(Duration.ofMinutes(minutes));
            }
        } else {
            end = Instant.now();
            start = end.minus(Duration.ofMinutes(minutes));
        }

        log.info("REST API [/aggregate]: measurement=[{}], field=[{}], bucket=[{}s], start=[{}], end=[{}]",
                measurement, field, bucketSeconds, start, end);

        try (QueryResult result = client.query()
                .measurement(measurement)
                .timeRange(start, end)
                .groupByTime(Duration.ofSeconds(bucketSeconds))
                .aggregate(field, AggregateType.COUNT, "countTemperature")
                .aggregate(field, AggregateType.AVG, "avgTemperature")
                .aggregate(field, AggregateType.MIN, "minTemperature")
                .aggregate(field, AggregateType.MAX, "maxTemperature")
                .execute()) {

            if (result.getRoot() != null) {
                log.info("Aggregate query result schema fields: {}", result.getRoot().getSchema().getFields());
            }

            List<AggregatedPointDto> data = result.toPojoList(AggregatedPointDto.class);

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "success");
            response.put("measurement", measurement);
            response.put("field", field);
            response.put("bucketSeconds", bucketSeconds);
            response.put("executionTimeMs", result.getExecutionTimeMs());
            response.put("totalRowCount", data.size());
            response.put("data", data);

            return response;
        }
    }

    /**
     * 3. 设备最新实时状态查询端点
     * GET /api/edge/telemetry/latest?deviceId=DEV_REG_001&measurement=regular_telemetry
     */
    @GetMapping("/latest")
    public Map<String, Object> getLatestState(
            @RequestParam(name = "deviceId", defaultValue = "DEV_REG_001") String deviceId,
            @RequestParam(name = "measurement", defaultValue = "regular_telemetry") String measurement) {

        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofHours(1));

        log.info("REST API [/latest]: Querying latest point for deviceId=[{}], measurement=[{}]", deviceId, measurement);

        try (QueryResult result = client.query()
                .measurement(measurement)
                .timeRange(start, end)
                .whereTag("device_id", deviceId)
                .orderByTimeDesc()
                .limit(1)
                .execute()) {

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "success");
            response.put("deviceId", deviceId);
            response.put("measurement", measurement);
            response.put("executionTimeMs", result.getExecutionTimeMs());

            if ("high_frequency_waveform".equalsIgnoreCase(measurement)) {
                List<HighFrequencyWaveformDto> list = result.toPojoList(HighFrequencyWaveformDto.class);
                response.put("latestData", list.isEmpty() ? null : list.get(0));
            } else {
                List<RegularTelemetryDto> list = result.toPojoList(RegularTelemetryDto.class);
                response.put("latestData", list.isEmpty() ? null : list.get(0));
            }

            return response;
        }
    }

    /**
     * 兼容接口: 常规数据快速入口
     */
    @GetMapping("/regular")
    public Map<String, Object> getRegularTelemetry(
            @RequestParam(name = "deviceId", defaultValue = "DEV_REG_001") String deviceId,
            @RequestParam(name = "minutes", defaultValue = "10") int minutes) {
        return queryHistory(deviceId, "regular_telemetry", minutes, null, null, 1000, "ASC");
    }

    /**
     * 兼容接口: 10kHz 高频示波器波形入口
     */
    @GetMapping("/high-frequency")
    public Map<String, Object> getHighFrequencyWaveform(
            @RequestParam(name = "deviceId", defaultValue = "DEV_HF_002") String deviceId,
            @RequestParam(name = "limit", defaultValue = "500") int limit) {
        return queryHistory(deviceId, "high_frequency_waveform", 5, null, null, limit, "ASC");
    }
}
