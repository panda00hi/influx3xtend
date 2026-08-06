package org.influx3xtend.core.client;

import org.influx3xtend.core.config.InfluxDB3Config;
import org.influx3xtend.core.duckdb.DuckDBEngineAdapter;
import org.influx3xtend.core.model.FilterCondition;
import org.influx3xtend.core.model.QueryRequest;
import org.influx3xtend.core.model.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TagColumnProjectionTest {

    @Test
    public void testFlightSqlAutomaticallyIncludesWhereTagColumns() {
        Influx3ClientAdapter adapter = new Influx3ClientAdapter(InfluxDB3Config.builder()
                .host("http://localhost:8181")
                .token("token")
                .database("tsdb")
                .build());

        QueryRequest req = new QueryRequest(
                "tsdb", "daq_card_a_10khz", List.of("vibration", "torque"),
                List.of(FilterCondition.eq("card_id", "DAQ_CARD_A")), List.of(),
                TimeRange.of(Instant.now().minusSeconds(100), Instant.now()),
                null, List.of(), List.of(), 10
        );

        String sql = adapter.buildSql(req);
        // 校验生成的 SQL 自动包含了过滤条件中的 card_id 字段
        assertThat(sql).startsWith("SELECT time, card_id, vibration, torque FROM daq_card_a_10khz");
    }

    @Test
    public void testDuckDbParquetAutomaticallyIncludesWhereTagColumns() {
        DuckDBEngineAdapter duckDb = new DuckDBEngineAdapter(null, "1GB");
        QueryRequest req = new QueryRequest(
                "tsdb", "daq_card_a_10khz", List.of("vibration"),
                List.of(FilterCondition.eq("device_id", "DEV_01")), List.of(),
                TimeRange.of(Instant.now().minusSeconds(100), Instant.now()),
                null, List.of(), List.of(), 10
        );

        String sql = duckDb.buildParquetSql(req);
        assertThat(sql).startsWith("SELECT time, device_id, vibration FROM read_parquet");
    }
}
