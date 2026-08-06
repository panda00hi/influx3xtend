package org.influx3xtend.example.dto;

import java.time.Instant;

/**
 * 采集设备 B 10kHz 高频采样数据 DTO (如：振动、转速、三相电流 U/V/W)
 */
public record HighFrequencyWaveformDto(
        Instant time,
        String deviceId,
        Double vibration,
        Double speedRpm,
        Double currentU,
        Double currentV,
        Double currentW
) {}
