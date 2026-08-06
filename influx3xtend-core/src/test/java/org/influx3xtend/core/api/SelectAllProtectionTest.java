package org.influx3xtend.core.api;

import org.influx3xtend.core.client.Influx3ClientAdapter;
import org.influx3xtend.core.duckdb.DuckDBEngineAdapter;
import org.influx3xtend.core.model.QueryResult;
import org.influx3xtend.core.router.StagedQueryRouter;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class SelectAllProtectionTest {

    @Test
    public void testSelectAllAppliesDefaultSafetyLimit() {
        StagedQueryRouter routerMock = mock(StagedQueryRouter.class);
        QueryResult resultMock = new QueryResult(null, 5);
        when(routerMock.routeAndExecute(any())).thenReturn(resultMock);

        Influx3QueryBuilder builder = new Influx3QueryBuilder(routerMock, "tsdb");
        builder.measurement("device_telemetry")
               .timeRange(Instant.now().minusSeconds(100), Instant.now())
               .execute();

        // 验证未传递 select 和 limit 时，自动赋予了 600000 行的安全 Limit 保护
        verify(routerMock).routeAndExecute(argThat(req -> req.limit() != null && req.limit() == 600000));
    }
}
