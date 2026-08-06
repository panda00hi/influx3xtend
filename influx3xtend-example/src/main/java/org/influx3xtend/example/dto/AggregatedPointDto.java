package org.influx3xtend.example.dto;

import java.time.Instant;

/**
 * 聚合统计 DTO (用于大跨度前端趋势折线图加速渲染)
 */
public record AggregatedPointDto(
        Instant time,
        Long countTemperature,
        Double avgTemperature,
        Double minTemperature,
        Double maxTemperature
) {}
