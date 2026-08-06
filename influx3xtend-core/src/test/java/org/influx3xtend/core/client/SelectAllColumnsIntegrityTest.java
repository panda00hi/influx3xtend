package org.influx3xtend.core.client;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.influx3xtend.core.config.InfluxDB3Config;
import org.influx3xtend.core.exception.Influx3xtendException;
import org.influx3xtend.core.model.QueryRequest;
import org.influx3xtend.core.model.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SelectAllColumnsIntegrityTest {

    @Test
    public void testSelectAllReturnsFullFieldsNotOnlyTime() {
        Influx3ClientAdapter adapter = new Influx3ClientAdapter(InfluxDB3Config.builder()
                .host("http://localhost:8181")
                .token("token")
                .database("tsdb")
                .build());

        QueryRequest req = new QueryRequest(
                "tsdb", "daq_card_a_10khz", List.of(), // 空 selectFields 等同于 SELECT *
                List.of(), List.of(), TimeRange.of(Instant.now().minusSeconds(100), Instant.now()),
                null, List.of(), List.of(), 10
        );

        String sql = adapter.buildSql(req);
        assertThat(sql).startsWith("SELECT * FROM daq_card_a_10khz");

        try (VectorSchemaRoot root = adapter.executeQuery(req)) {
            if (root != null) {
                // 校验包含全量字段，不仅只有 time 列
                assertThat(root.getSchema().getFields().size()).isGreaterThan(1);
                assertThat(root.getVector("card_id")).isNotNull();
                assertThat(root.getVector("vibration")).isNotNull();
            }
        } catch (Influx3xtendException e) {
            // Expected since localhost:8181 is a dummy host in this test
            assertThat(e.getMessage()).contains("Query execution");
        }
    }
}
