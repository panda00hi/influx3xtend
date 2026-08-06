package org.influx3xtend.core.client;

import org.influx3xtend.core.config.InfluxDB3Config;
import org.influx3xtend.core.duckdb.DuckDBEngineAdapter;
import org.influx3xtend.core.model.AggregateExpr;
import org.influx3xtend.core.model.AggregateType;
import org.influx3xtend.core.model.QueryRequest;
import org.influx3xtend.core.model.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ColumnProjectionPushdownTest {

    @Test
    public void testFlightSqlProjectionPushdown() {
        Influx3ClientAdapter adapter = new Influx3ClientAdapter(InfluxDB3Config.builder()
                .host("http://localhost:8181")
                .token("token")
                .database("tsdb")
                .build());

        QueryRequest req = new QueryRequest(
                "tsdb", "device_telemetry", List.of("temperature", "humidity"),
                List.of(), List.of(), TimeRange.of(Instant.now().minusSeconds(100), Instant.now()),
                null, List.of(), List.of(), 10
        );

        String sql = adapter.buildSql(req);
        assertThat(sql).startsWith("SELECT time, temperature, humidity FROM device_telemetry");
        assertThat(sql).doesNotContain("SELECT *");
    }

    @Test
    public void testDuckDbParquetProjectionPushdown() {
        DuckDBEngineAdapter duckDb = new DuckDBEngineAdapter(null, "1GB");
        QueryRequest req = new QueryRequest(
                "tsdb", "device_telemetry", List.of("vibration"),
                List.of(), List.of(), TimeRange.of(Instant.now().minusSeconds(100), Instant.now()),
                null, List.of(), List.of(), 10
        );

        String sql = duckDb.buildParquetSql(req);
        assertThat(sql).startsWith("SELECT time, vibration FROM read_parquet");
        assertThat(sql).doesNotContain("SELECT *");
    }

    @Test
    public void testFlightSqlGroupByTimeAggregation() {
        Influx3ClientAdapter adapter = new Influx3ClientAdapter(InfluxDB3Config.builder()
                .host("http://localhost:8181")
                .token("token")
                .database("tsdb")
                .build());

        QueryRequest req = new QueryRequest(
                "tsdb", "regular_telemetry", "time", List.of(),
                List.of(), List.of(), TimeRange.of(Instant.now().minusSeconds(100), Instant.now()),
                Duration.ofSeconds(5),
                List.of(
                        AggregateExpr.of("temperature", AggregateType.COUNT, "countTemperature"),
                        AggregateExpr.of("temperature", AggregateType.AVG, "avgTemperature")
                ),
                List.of(), 100
        );

        String sql = adapter.buildSql(req);
        assertThat(sql).contains("DATE_BIN(INTERVAL '5 SECOND', time");
        assertThat(sql).contains("COUNT(temperature) AS countTemperature");
        assertThat(sql).contains("AVG(temperature) AS avgTemperature");
        assertThat(sql).contains("GROUP BY 1 ORDER BY 1 ASC");
    }

    @Test
    public void testDuckDbParquetGroupByTimeAggregation() {
        DuckDBEngineAdapter duckDb = new DuckDBEngineAdapter(null, "1GB");
        QueryRequest req = new QueryRequest(
                "tsdb", "regular_telemetry", "time", List.of(),
                List.of(), List.of(), TimeRange.of(Instant.now().minusSeconds(100), Instant.now()),
                Duration.ofSeconds(5),
                List.of(
                        AggregateExpr.of("temperature", AggregateType.COUNT, "countTemperature"),
                        AggregateExpr.of("temperature", AggregateType.AVG, "avgTemperature")
                ),
                List.of(), 100
        );

        String sql = duckDb.buildParquetSql(req, List.of("s3://bucket/test.parquet"));
        assertThat(sql).contains("time_bucket(INTERVAL '5 SECONDS', time)");
        assertThat(sql).contains("COUNT(temperature) AS countTemperature");
        assertThat(sql).contains("AVG(temperature) AS avgTemperature");
        assertThat(sql).contains("GROUP BY 1 ORDER BY 1 ASC");
    }
}
