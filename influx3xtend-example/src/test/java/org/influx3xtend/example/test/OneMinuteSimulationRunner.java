package org.influx3xtend.example.test;

import org.influx3xtend.core.api.Influx3xtendClient;
import org.influx3xtend.core.config.InfluxDB3Config;
import org.influx3xtend.core.config.LocalStorageConfig;
import org.influx3xtend.core.config.StorageConfig;
import org.influx3xtend.core.duckdb.DuckDBEngineAdapter;
import org.influx3xtend.core.model.QueryResult;
import org.influx3xtend.example.converter.MqttLineProtocolConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 1 分钟真实环境压测与全链路自动化模拟器 (OneMinuteSimulationRunner)
 */
public class OneMinuteSimulationRunner {

    private static final Logger log = LoggerFactory.getLogger(OneMinuteSimulationRunner.class);

    private static final String DEVICE_ID = "DEV_SIM_1MIN_01";
    private static final String MEASUREMENT = "simulation_telemetry";

    public static void main(String[] args) {
        log.info("==========================================================================");
        log.info("🚀 启动 1 分钟 Influx3xtend 真实压测与混合引擎路线校验模拟器");
        log.info("==========================================================================");

        long startEpochMs = System.currentTimeMillis();
        long runDurationMs = 5000; // 快速集成测试模型 (5s)

        StorageConfig storageConfig = LocalStorageConfig.of(System.getProperty("user.home") + "/.influxdb/data");
        InfluxDB3Config influx3Config = InfluxDB3Config.builder()
                .host("http://localhost:8181")
                .token(System.getenv().getOrDefault("INFLUXDB3_TOKEN", "apiv3_-VpAAyseQdfg0gHFV58xLIg90xsXzL-Ww3G1z8dkJNpWBT1Os-07ox5mCpBGc16-lJF1x35SpnZWPh_0AoRtcQ"))
                .database("edge_iot_db")
                .build();

        AtomicLong generatedJsonRecords = new AtomicLong(0);
        AtomicLong ingestedRecords = new AtomicLong(0);

        try (Influx3xtendClient client = Influx3xtendClient.builder()
                .influx3Config(influx3Config)
                .storageConfig(storageConfig)
                .build();
             DuckDBEngineAdapter duckDBEngineAdapter = new DuckDBEngineAdapter(storageConfig, "2GB")) {

            Random random = new Random();
            List<String> batchBuffer = new ArrayList<>();

            while (System.currentTimeMillis() - startEpochMs < runDurationMs) {
                long currentTs = System.currentTimeMillis();
                double temp = 20.0 + random.nextDouble() * 15.0;
                double vib = 0.05 + random.nextDouble() * 0.5;

                String singleJson = String.format("{\"time\":%d,\"temperature\":%.2f,\"vibration\":%.2f}", currentTs, temp, vib);
                batchBuffer.add(singleJson);
                generatedJsonRecords.incrementAndGet();

                if (batchBuffer.size() >= 50) {
                    String batchJsonStr = "[" + String.join(",", batchBuffer) + "]";
                    batchBuffer.clear();

                    String lineProtocol = MqttLineProtocolConverter.toLineProtocol(batchJsonStr, DEVICE_ID, MEASUREMENT);
                    if (lineProtocol != null && !lineProtocol.isBlank()) {
                        client.writeRecord(lineProtocol);
                        ingestedRecords.addAndGet(50);
                    }
                }
                Thread.sleep(2);
            }

            if (!batchBuffer.isEmpty()) {
                String batchJsonStr = "[" + String.join(",", batchBuffer) + "]";
                String lineProtocol = MqttLineProtocolConverter.toLineProtocol(batchJsonStr, DEVICE_ID, MEASUREMENT);
                if (lineProtocol != null && !lineProtocol.isBlank()) {
                    client.writeRecord(lineProtocol);
                    ingestedRecords.addAndGet(batchBuffer.size());
                }
            }

            log.info("1 分钟压测运行完成！已生成 JSON 记录: {}, 已接入线协议点数: {}",
                    generatedJsonRecords.get(), ingestedRecords.get());

            // 执行校验查询
            Instant end = Instant.now();
            Instant start = end.minus(Duration.ofMinutes(15));
            try (QueryResult res = client.query()
                    .measurement(MEASUREMENT)
                    .timeRange(start, end)
                    .whereTag("device_id", DEVICE_ID)
                    .limit(10)
                    .execute()) {

                log.info("查询验证: 返回记录数 = {}", res.getRowCount());
            }

        } catch (Exception e) {
            log.error("1 分钟模拟器运行异常", e);
        }
    }
}
