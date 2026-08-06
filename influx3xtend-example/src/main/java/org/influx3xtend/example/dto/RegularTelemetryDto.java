package org.influx3xtend.example.dto;

import java.time.Instant;

/**
 * 采集设备 A 常规数据 DTO (如：温湿度、电机运行状态)
 */
public record RegularTelemetryDto(
        Instant time,
        String deviceId,
        Double temperature,
        Double humidity,
        Integer motorState
) {}
