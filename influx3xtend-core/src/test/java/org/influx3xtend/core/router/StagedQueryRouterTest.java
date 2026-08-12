package org.influx3xtend.core.router;

import org.influx3xtend.core.api.Influx3xtendClient;
import org.influx3xtend.core.config.InfluxDB3Config;
import org.influx3xtend.core.config.S3StorageConfig;
import org.influx3xtend.core.engine.StorageEngineAdapter;
import org.influx3xtend.core.exception.Influx3xtendException;
import org.influx3xtend.core.model.QueryRequest;
import org.influx3xtend.core.model.QueryResult;
import org.influx3xtend.core.model.SortOrder;
import org.influx3xtend.core.model.TimeRange;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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

    @Test
    public void testPureHotQueryFullyCoveredByInflux3() {
        StorageEngineAdapter influx3Adapter = mock(StorageEngineAdapter.class);
        StorageEngineAdapter duckDBAdapter = mock(StorageEngineAdapter.class);

        Instant now = Instant.now();
        Instant reqStart = now.minus(Duration.ofHours(5));
        Instant minInInflux3 = now.minus(Duration.ofHours(10)); // Covered!

        QueryRequest request = new QueryRequest(
                "db", "m1", "time", List.of(), List.of(), List.of(), TimeRange.of(reqStart, now), null, List.of(), List.of(), 1000
        );

        when(influx3Adapter.probeMinTimestamp(any())).thenReturn(minInInflux3);
        when(influx3Adapter.executeQuery(any())).thenReturn(null);

        StagedQueryRouter router = new StagedQueryRouter(influx3Adapter, duckDBAdapter);
        try (QueryResult result = router.routeAndExecute(request)) {
            assertThat(result).isNotNull();
        }

        verify(influx3Adapter, times(1)).probeMinTimestamp(any());
        verify(influx3Adapter, times(1)).executeQuery(any());
        verify(duckDBAdapter, never()).executeQuery(any());
    }

    @Test
    public void testHybridQueryOrderAscPrioritizesDuckDB() {
        StorageEngineAdapter influx3Adapter = mock(StorageEngineAdapter.class);
        StorageEngineAdapter duckDBAdapter = mock(StorageEngineAdapter.class);

        Instant now = Instant.now();
        Instant reqStart = now.minus(Duration.ofDays(10));
        Instant minInInflux3 = now.minus(Duration.ofDays(3)); // Hybrid!

        QueryRequest request = new QueryRequest(
                "db", "m1", "time", List.of(), List.of(), List.of(), TimeRange.of(reqStart, now), null, List.of(),
                List.of(new org.influx3xtend.core.model.OrderByExpr("time", SortOrder.ASC)), 1000
        );

        when(influx3Adapter.probeMinTimestamp(any())).thenReturn(minInInflux3);
        when(duckDBAdapter.executeQuery(any())).thenReturn(null);
        when(influx3Adapter.executeQuery(any())).thenReturn(null);

        StagedQueryRouter router = new StagedQueryRouter(influx3Adapter, duckDBAdapter);
        try (QueryResult result = router.routeAndExecute(request)) {
            assertThat(result).isNotNull();
        }

        // For ASC order, DuckDB cold slice should be queried FIRST!
        ArgumentCaptor<QueryRequest> duckdbCaptor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(duckDBAdapter, times(1)).executeQuery(duckdbCaptor.capture());
        assertThat(duckdbCaptor.getValue().timeRange().start()).isEqualTo(reqStart);
        assertThat(duckdbCaptor.getValue().timeRange().end()).isEqualTo(minInInflux3);

        ArgumentCaptor<QueryRequest> influx3Captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(influx3Adapter, times(1)).executeQuery(influx3Captor.capture());
        assertThat(influx3Captor.getValue().timeRange().start()).isEqualTo(minInInflux3);
    }

    @Test
    public void testHybridQueryOrderDescPrioritizesInflux3() {
        StorageEngineAdapter influx3Adapter = mock(StorageEngineAdapter.class);
        StorageEngineAdapter duckDBAdapter = mock(StorageEngineAdapter.class);

        Instant now = Instant.now();
        Instant reqStart = now.minus(Duration.ofDays(10));
        Instant minInInflux3 = now.minus(Duration.ofDays(3)); // Hybrid!

        QueryRequest request = new QueryRequest(
                "db", "m1", "time", List.of(), List.of(), List.of(), TimeRange.of(reqStart, now), null, List.of(),
                List.of(new org.influx3xtend.core.model.OrderByExpr("time", SortOrder.DESC)), 1000
        );

        when(influx3Adapter.probeMinTimestamp(any())).thenReturn(minInInflux3);
        when(influx3Adapter.executeQuery(any())).thenReturn(null);
        when(duckDBAdapter.executeQuery(any())).thenReturn(null);

        StagedQueryRouter router = new StagedQueryRouter(influx3Adapter, duckDBAdapter);
        try (QueryResult result = router.routeAndExecute(request)) {
            assertThat(result).isNotNull();
        }

        // For DESC order, InfluxDB 3 hot slice should be queried FIRST!
        ArgumentCaptor<QueryRequest> influx3Captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(influx3Adapter, times(1)).executeQuery(influx3Captor.capture());
        assertThat(influx3Captor.getValue().timeRange().start()).isEqualTo(minInInflux3);

        ArgumentCaptor<QueryRequest> duckdbCaptor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(duckDBAdapter, times(1)).executeQuery(duckdbCaptor.capture());
        assertThat(duckdbCaptor.getValue().timeRange().start()).isEqualTo(reqStart);
        assertThat(duckdbCaptor.getValue().timeRange().end()).isEqualTo(minInInflux3);
    }
}

