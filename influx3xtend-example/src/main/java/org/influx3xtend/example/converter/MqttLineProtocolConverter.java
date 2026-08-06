package org.influx3xtend.example.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 边缘 MQTT 报文直转 InfluxDB 3 Line Protocol 转换器 (MqttLineProtocolConverter)
 * 具备极佳的高并发性能：
 * 1. 支持工业 10kHz 点阵格式 (ts + interval + fields + values) 展开为 Line Protocol
 * 2. 支持通用 JSON 对象/数组格式直转 Line Protocol
 * 3. 彻底摆脱中转 Arrow 向量的额外 CPU/内存开销
 */
public final class MqttLineProtocolConverter {

    private static final Logger log = LoggerFactory.getLogger(MqttLineProtocolConverter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private MqttLineProtocolConverter() {}

    /**
     * 将 JSON 报文解析并直接格式化为 InfluxDB 3 Line Protocol 字符串（单条或多条换行拼接）
     */
    public static String toLineProtocol(String jsonPayload, String deviceId, String measurement) {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            return null;
        }

        try {
            JsonNode rootNode = objectMapper.readTree(jsonPayload);
            if (rootNode == null || rootNode.isEmpty()) {
                return null;
            }

            String targetMeasurement = (measurement != null && !measurement.isBlank())
                    ? measurement
                    : "device_telemetry";

            // 1. 10kHz 工业二维数组点阵格式 (ts + interval + fields + values)
            if (rootNode.isObject() && rootNode.has("ts") && rootNode.has("fields") && rootNode.has("values")) {
                return convert10kHzBatch(rootNode, deviceId, targetMeasurement);
            }

            // 2. 通用 JSON 数组/对象格式
            List<JsonNode> records = new ArrayList<>();
            if (rootNode.isArray()) {
                for (JsonNode r : rootNode) records.add(r);
            } else if (rootNode.isObject()) {
                records.add(rootNode);
            }

            if (records.isEmpty()) return null;

            StringBuilder sb = new StringBuilder();
            String headerPrefix = deviceId != null && !deviceId.isBlank()
                    ? targetMeasurement + ",device_id=" + sanitizeTag(deviceId) + " "
                    : targetMeasurement + " ";

            for (JsonNode record : records) {
                if (!record.isObject()) continue;

                sb.append(headerPrefix);
                boolean firstField = true;

                Iterator<Map.Entry<String, JsonNode>> fieldsIter = record.fields();
                while (fieldsIter.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fieldsIter.next();
                    String key = entry.getKey();
                    if ("time".equalsIgnoreCase(key) || "device_id".equalsIgnoreCase(key)) continue;

                    JsonNode valNode = entry.getValue();
                    if (valNode == null || valNode.isNull()) continue;

                    if (!firstField) sb.append(",");
                    sb.append(key).append("=");

                    if (valNode.isNumber()) {
                        sb.append(valNode.asText());
                    } else if (valNode.isBoolean()) {
                        sb.append(valNode.asBoolean());
                    } else {
                        sb.append("\"").append(valNode.asText().replace("\"", "\\\"")).append("\"");
                    }
                    firstField = false;
                }

                if (firstField) {
                    // 没有有效 field，放弃该行
                    continue;
                }

                // 时间戳解析
                long tsNs = System.currentTimeMillis() * 1_000_000L;
                if (record.has("time")) {
                    JsonNode timeNode = record.get("time");
                    if (timeNode.isNumber()) {
                        long tVal = timeNode.asLong();
                        tsNs = tVal < 1_000_000_000_000_000L ? tVal * 1_000_000L : tVal;
                    } else if (timeNode.isTextual()) {
                        try {
                            tsNs = Instant.parse(timeNode.asText()).toEpochMilli() * 1_000_000L;
                        } catch (Exception ignored) {}
                    }
                }

                sb.append(" ").append(tsNs).append("\n");
            }

            return sb.toString();

        } catch (Exception e) {
            log.warn("Failed to convert JSON payload to Line Protocol: {}", e.getMessage());
            return null;
        }
    }

    private static String convert10kHzBatch(JsonNode rootNode, String deviceId, String measurement) {
        long baseTsNs = rootNode.get("ts").asLong();
        long intervalNs = rootNode.has("interval") ? rootNode.get("interval").asLong() : 1_000_000L;

        List<String> fieldNames = new ArrayList<>();
        for (JsonNode fn : rootNode.get("fields")) {
            fieldNames.add(fn.asText());
        }

        JsonNode valuesArray = rootNode.get("values");
        if (!valuesArray.isArray() || valuesArray.isEmpty()) return null;

        StringBuilder sb = new StringBuilder(valuesArray.size() * 128);
        String headerPrefix = deviceId != null && !deviceId.isBlank()
                ? measurement + ",device_id=" + sanitizeTag(deviceId) + " "
                : measurement + " ";

        int rowCount = valuesArray.size();
        for (int i = 0; i < rowCount; i++) {
            JsonNode row = valuesArray.get(i);
            if (!row.isArray()) continue;

            sb.append(headerPrefix);
            boolean firstField = true;
            int colCount = Math.min(fieldNames.size(), row.size());

            for (int c = 0; c < colCount; c++) {
                JsonNode val = row.get(c);
                if (val == null || val.isNull()) continue;

                if (!firstField) sb.append(",");
                sb.append(fieldNames.get(c)).append("=");
                if (val.isNumber()) {
                    sb.append(val.asText());
                } else if (val.isBoolean()) {
                    sb.append(val.asBoolean());
                } else {
                    sb.append("\"").append(val.asText().replace("\"", "\\\"")).append("\"");
                }
                firstField = false;
            }

            if (!firstField) {
                long currentTsNs = baseTsNs + (i * intervalNs);
                sb.append(" ").append(currentTsNs).append("\n");
            }
        }

        return sb.toString();
    }

    private static String sanitizeTag(String tagValue) {
        return tagValue.replace(" ", "\\ ")
                .replace(",", "\\,")
                .replace("=", "\\=");
    }
}
