package org.influx3xtend.example.test;

import org.influx3xtend.example.converter.MqttLineProtocolConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MQTT JSON 边缘推流与 InfluxDB 3 Line Protocol 转换接入示例
 */
public class MqttIngestionExample {

    private static final Logger log = LoggerFactory.getLogger(MqttIngestionExample.class);

    public static void main(String[] args) {
        String mockMqttPayload = """
                {
                  "ts": 1785223800000000000,
                  "interval": 100000,
                  "fields": ["rpm", "vibration", "temp"],
                  "values": [
                    [1500, 0.12, 45.2],
                    [1502, 0.15, 45.3],
                    [1505, 0.88, 48.1]
                  ]
                }
                """;

        try {
            // 解析 MQTT 报文转换为 InfluxDB 3 Line Protocol 字符串
            String lineProtocol = MqttLineProtocolConverter.toLineProtocol(mockMqttPayload, "DEV_EDGE_CARD_01", "high_frequency_waveform");
            if (lineProtocol != null) {
                log.info("Converted Line Protocol Output:\n{}", lineProtocol);
            } else {
                log.warn("Failed to convert MQTT payload.");
            }
        } catch (Exception e) {
            log.error("MQTT Example execution failed", e);
        }
    }
}
