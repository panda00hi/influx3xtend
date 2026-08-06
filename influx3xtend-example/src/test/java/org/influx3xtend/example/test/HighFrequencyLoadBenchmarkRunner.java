package org.influx3xtend.example.test;

import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.config.ClientConfig;
import org.influx3xtend.core.api.Influx3xtendClient;
import org.influx3xtend.core.config.InfluxDB3Config;
import org.influx3xtend.core.config.S3StorageConfig;
import org.influx3xtend.core.model.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 示例 3: 10kHz 高频大吞吐 1 分钟持续接入与盘点测试 Runner (HighFrequencyLoadBenchmarkRunner)
 */
public class HighFrequencyLoadBenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(HighFrequencyLoadBenchmarkRunner.class);

    private static final String HOST = "http://localhost:8181";
    private static final String TOKEN = System.getenv().getOrDefault("INFLUXDB3_TOKEN", "apiv3_-VpAAyseQdfg0gHFV58xLIg90xsXzL-Ww3G1z8dkJNpWBT1Os-07ox5mCpBGc16-lJF1x35SpnZWPh_0AoRtcQ");
    private static final String DATABASE = "tsdb";

    public static void main(String[] args) {
        log.info("==========================================================================");
        log.info(" Start 1-Minute Continuous 10kHz Ingestion & Query Benchmark Test ");
        log.info(" Expected Ingestion Total: 1,200,000 Points (60s x 20,000 pts/s) ");
        log.info("==========================================================================");

        ClientConfig clientConfig = new ClientConfig.Builder()
                .host(HOST)
                .token(TOKEN.toCharArray())
                .database(DATABASE)
                .build();

        AtomicLong totalInjectedPoints = new AtomicLong(0);
        long startTime = System.currentTimeMillis();

        try (InfluxDBClient rawWriteClient = InfluxDBClient.getInstance(clientConfig);
             Influx3xtendClient queryClient = Influx3xtendClient.builder()
                     .influx3Config(InfluxDB3Config.builder().host(HOST).token(TOKEN).database(DATABASE).build())
                     .storageConfig(S3StorageConfig.builder().bucket("node0").endpoint(HOST).build())
                     .duckdbMaxMemory("4GB")
                     .build();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // 1. 虚拟线程后台任务：持续 60 秒（1 分钟）高速并发写入
            var writeFuture = executor.submit(() -> {
                for (int sec = 1; sec <= 60; sec++) {
                    StringBuilder sbA = new StringBuilder(10000 * 50);
                    StringBuilder sbB = new StringBuilder(10000 * 50);
                    long baseTsNs = System.currentTimeMillis() * 1_000_000L;

                    for (int i = 0; i < 10000; i++) {
                        long tsNs = baseTsNs + (i * 100000L);
                        sbA.append("daq_card_a_10khz,card_id=DAQ_CARD_A vibration=")
                           .append(0.1 + (i * 0.0001))
                           .append(",torque=").append(300.0 + (i * 0.01))
                           .append(" ").append(tsNs).append("\n");

                        sbB.append("daq_card_b_10khz,card_id=DAQ_CARD_B current_u=")
                           .append(100.0 + (i * 0.01))
                           .append(",current_v=").append(-50.0)
                           .append(",current_w=").append(-50.0)
                           .append(" ").append(tsNs).append("\n");
                    }

                    rawWriteClient.writeRecord(sbA.toString());
                    rawWriteClient.writeRecord(sbB.toString());
                    long currentTotal = totalInjectedPoints.addAndGet(20000);

                    if (sec % 10 == 0 || sec == 60) {
                        log.info("[Ingestion Progress] Second {}/60 | Cumulative Injected Points: {}", sec, currentTotal);
                    }

                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            });

            // 2. 主线程持续 60 秒并发流式查询
            int queryCount = 0;
            long totalExecutionTimeMs = 0;

            while (!writeFuture.isDone() || (System.currentTimeMillis() - startTime < 60000)) {
                try (QueryResult res = queryClient.query()
                        .measurement("daq_card_a_10khz")
                        .select("vibration", "torque")
                        .whereTag("card_id", "DAQ_CARD_A")
                        .timeRange(Instant.now().minus(Duration.ofMinutes(5)), Instant.now())
                        .limit(50)
                        .execute()) {

                    queryCount++;
                    totalExecutionTimeMs += res.getExecutionTimeMs();
                    if (queryCount % 10 == 0) {
                        log.info("[Query Progress] Completed Query #{}: Returned {} rows in {} ms",
                                queryCount, res.getRowCount(), res.getExecutionTimeMs());
                    }
                }

                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }

            writeFuture.get(); // 等待写入任务完成

            long durationSec = (System.currentTimeMillis() - startTime) / 1000;
            double avgLatency = queryCount > 0 ? (double) totalExecutionTimeMs / queryCount : 0.0;

            log.info("\n==========================================================================");
            log.info(" 1-Minute Load Test Execution Completed! ");
            log.info(" Total Duration: {} seconds", durationSec);
            log.info(" Total Injected Points: {} rows (20,000 pts/s)", totalInjectedPoints.get());
            log.info(" Total Streaming Queries: {}", queryCount);
            log.info(" Average Streaming Query Latency: {} ms", String.format("%.2f", avgLatency));
            log.info("==========================================================================");

        } catch (Exception e) {
            log.error("Fatal error during 1-minute load test", e);
        }
    }
}
