package org.influx3xtend.core.router;

import org.influx3xtend.core.api.Influx3xtendClient;
import org.influx3xtend.core.config.InfluxDB3Config;
import org.influx3xtend.core.config.S3StorageConfig;
import org.influx3xtend.core.exception.Influx3xtendException;
import org.influx3xtend.core.model.QueryResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class StagedQueryRouterTest {

    @Test
    public void testClientQueryBuilderInitialization() {
        Influx3xtendClient client = Influx3xtendClient.builder()
                .influx3Config(InfluxDB3Config.builder()
                        .host("http://localhost:8086")
                        .token("dummy-token")
                        .database("test-db")
                        .build())
                .storageConfig(S3StorageConfig.builder()
                        .bucket("test-bucket")
                        .build())
                .build();

        assertThat(client).isNotNull();

        try (QueryResult result = client.query()
                .measurement("test_metric")
                .timeRange(Instant.now().minus(Duration.ofHours(1)), Instant.now())
                .execute()) {

            assertThat(result).isNotNull();
            assertThat(result.getExecutionTimeMs()).isGreaterThanOrEqualTo(0);
        } catch (Influx3xtendException e) {
            // Expected since localhost:8086 is a dummy host in this test
            assertThat(e.getMessage()).containsIgnoringCase("query");
        }
    }
}
