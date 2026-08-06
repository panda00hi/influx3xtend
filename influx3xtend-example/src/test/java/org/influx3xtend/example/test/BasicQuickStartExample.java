package org.influx3xtend.example.test;

import org.influx3xtend.core.api.Influx3xtendClient;
import org.influx3xtend.core.config.InfluxDB3Config;
import org.influx3xtend.core.config.S3StorageConfig;
import org.influx3xtend.core.model.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 示例 1: 基础 QuickStart 范例 (BasicQuickStartExample)
 * 验证：未指定 select 字段时，默认查 SELECT * 全量列！100% 呈现完整业务字段，绝对不只返回 time 列！
 */
public class BasicQuickStartExample {

    private static final Logger log = LoggerFactory.getLogger(BasicQuickStartExample.class);

    private static final String HOST = "http://localhost:8181";
    private static final String TOKEN = System.getenv().getOrDefault("INFLUXDB3_TOKEN", "apiv3_-VpAAyseQdfg0gHFV58xLIg90xsXzL-Ww3G1z8dkJNpWBT1Os-07ox5mCpBGc16-lJF1x35SpnZWPh_0AoRtcQ");
    private static final String DATABASE = "tsdb";

    public static void main(String[] args) {
        log.info("==========================================================================");
        log.info(" Example 1: Default SELECT * Full Fields Verification Demonstration ");
        log.info("==========================================================================");

        // 1. 初始化通用 Influx3xtend 客户端
        try (Influx3xtendClient client = Influx3xtendClient.builder()
                .influx3Config(InfluxDB3Config.builder()
                        .host(HOST)
                        .token(TOKEN)
                        .database(DATABASE)
                        .build())
                .localStorageDir("./data/parquet") // 使用本地磁盘目录模式（无需 S3 / MinIO）
                .duckdbMaxMemory("2GB")
                .build()) {

            // 2. 发起未指定 select(...) 的默认 SELECT * 查询
            try (QueryResult result = client.query()
                    .measurement("daq_card_a_10khz")
                    .whereTag("card_id", "DAQ_CARD_A")
                    .timeRange(Instant.now().minus(Duration.ofHours(24)), Instant.now())
                    .limit(20)
                    .execute()) {

                log.info("QueryResult Row Count: {}", result.getRowCount());
                log.info("Execution Time: {} ms", result.getExecutionTimeMs());
                log.info("Realtime Slice Rows: {}, Historical Slice Rows: {}",
                        result.getRealtimeRows(), result.getHistoricalRows());

                // 方式 A：无模板格式导出为 Map 列表 (验证 SELECT * 返回的全量列集合)
                List<Map<String, Object>> mapRows = result.toMapList();
                log.info("Fetched Generic Map Rows Size: {}", mapRows.size());
                if (!mapRows.isEmpty()) {
                    log.info("SELECT * Full Fields Map Row [0]: {}", mapRows.get(0));
                }

                // 方式 B：映射为真实全字段 POJO 类
                List<RealCardAPojo> pojoList = result.toPojoList(RealCardAPojo.class);
                log.info("Mapped Real Data POJO List Size: {}", pojoList.size());
                if (!pojoList.isEmpty()) {
                    RealCardAPojo p = pojoList.get(0);
                    log.info("Mapped Real Data Pojo [0] -> time: {}, card_id: {}, vibration: {}, torque: {}",
                            p.getTime(), p.getCard_id(), p.getVibration(), p.getTorque());
                }
            }

        } catch (Exception e) {
            log.error("QuickStart Example execution failed", e);
        }
    }

    /**
     * 物理表的全字段 POJO
     */
    public static class RealCardAPojo {
        private Instant time;
        private String card_id;
        private Double vibration;
        private Double torque;

        public Instant getTime() { return time; }
        public void setTime(Instant time) { this.time = time; }
        public String getCard_id() { return card_id; }
        public void setCard_id(String card_id) { this.card_id = card_id; }
        public Double getVibration() { return vibration; }
        public void setVibration(Double vibration) { this.vibration = vibration; }
        public Double getTorque() { return torque; }
        public void setTorque(Double torque) { this.torque = torque; }
    }
}
