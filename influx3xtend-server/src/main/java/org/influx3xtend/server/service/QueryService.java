package org.influx3xtend.server.service;

import org.influx3xtend.core.api.Influx3QueryBuilder;
import org.influx3xtend.core.api.Influx3xtendClient;
import org.influx3xtend.core.model.QueryResult;
import org.influx3xtend.core.model.SortOrder;
import org.influx3xtend.server.dto.QueryDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 查询网关业务层服务 (QueryService)
 * 封装底层的 Influx3xtendClient，处理强类型 QueryDTO 绑定与零硬编码业务逻辑
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private final Influx3xtendClient client;

    public QueryService(Influx3xtendClient client) {
        this.client = client;
    }

    /**
     * 处理强类型 REST 查询请求 (QueryDTO)
     */
    public Map<String, Object> executeQuery(QueryDTO dto) {
        if (dto == null || dto.measurement() == null || dto.measurement().isBlank()) {
            throw new IllegalArgumentException("Field 'measurement' is required in query request payload.");
        }

        int limit = dto.limit() != null ? dto.limit() : 100;

        Instant startTime;
        Instant endTime;

        if (dto.startTime() != null && !dto.startTime().isBlank()) {
            startTime = Instant.parse(dto.startTime());
        } else {
            int hoursAgo = dto.hoursAgo() != null ? dto.hoursAgo() : 24;
            startTime = Instant.now().minus(Duration.ofHours(hoursAgo));
        }

        if (dto.endTime() != null && !dto.endTime().isBlank()) {
            endTime = Instant.parse(dto.endTime());
        } else {
            endTime = Instant.now();
        }

        Influx3QueryBuilder builder = client.query()
                .measurement(dto.measurement())
                .timeRange(startTime, endTime)
                .limit(limit);

        if (dto.timeColumn() != null && !dto.timeColumn().isBlank()) {
            builder.timeColumn(dto.timeColumn());
        }

        if (dto.selectFields() != null) {
            for (String field : dto.selectFields()) {
                if (field != null && !field.isBlank()) {
                    builder.select(field);
                }
            }
        }

        if (dto.tags() != null) {
            for (Map.Entry<String, String> entry : dto.tags().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    builder.whereTag(entry.getKey(), entry.getValue());
                }
            }
        }

        if (dto.orderBy() != null && !dto.orderBy().isBlank()) {
            SortOrder sortOrder = "DESC".equalsIgnoreCase(dto.order()) ? SortOrder.DESC : SortOrder.ASC;
            builder.orderBy(dto.orderBy(), sortOrder);
        } else if ("DESC".equalsIgnoreCase(dto.order())) {
            builder.orderByTimeDesc();
        }

        try (QueryResult result = builder.execute()) {
            List<Map<String, Object>> dataRows = result.toMapList();

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "success");
            response.put("rowCount", result.getRowCount());
            response.put("realtimeRows", result.getRealtimeRows());
            response.put("historicalRows", result.getHistoricalRows());
            response.put("executionTimeMs", result.getExecutionTimeMs());
            response.put("data", dataRows);

            return response;
        }
    }
}
