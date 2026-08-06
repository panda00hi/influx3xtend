package org.influx3xtend.core.api;

import org.influx3xtend.core.client.Influx3ClientAdapter;
import org.influx3xtend.core.config.Influx3xtendConfig;
import org.influx3xtend.core.config.InfluxDB3Config;
import org.influx3xtend.core.config.LocalStorageConfig;
import org.influx3xtend.core.config.S3StorageConfig;
import org.influx3xtend.core.config.StorageConfig;
import org.influx3xtend.core.duckdb.DuckDBEngineAdapter;
import org.influx3xtend.core.router.StagedQueryRouter;

/**
 * Influx3xtend 中间件入口客户端 (Influx3xtendClient)
 * 封装底层的 Influx3 客户端、DuckDB 引擎与 Staged Query Router
 */
public class Influx3xtendClient implements AutoCloseable {

    private final Influx3xtendConfig config;
    private final Influx3ClientAdapter influx3Adapter;
    private final DuckDBEngineAdapter duckDBAdapter;
    private final StagedQueryRouter router;

    private Influx3xtendClient(Influx3xtendConfig config) {
        this.config = config;
        this.influx3Adapter = new Influx3ClientAdapter(config.influx3Config());
        this.duckDBAdapter = new DuckDBEngineAdapter(config.storageConfig(), config.duckdbMaxMemory());
        this.router = new StagedQueryRouter(influx3Adapter, duckDBAdapter, config.influx3Config().maxNativeQueryWindow());
    }

    public static Builder builder() {
        return new Builder();
    }

    public Influx3QueryBuilder query() {
        return new Influx3QueryBuilder(router, config.influx3Config().database());
    }

    /**
     * 向 InfluxDB 3 Core 实时写入单条或带换行符的多条 Line Protocol 数据记录
     */
    public void writeRecord(String lineProtocol) {
        influx3Adapter.writeRecord(lineProtocol);
    }

    /**
     * 向 InfluxDB 3 Core 批量写入 Line Protocol 记录集合
     */
    public void writeRecords(Iterable<String> lineProtocols) {
        influx3Adapter.writeRecords(lineProtocols);
    }

    @Override
    public void close() {
        try {
            influx3Adapter.close();
        } catch (Exception ignored) {}
        try {
            duckDBAdapter.close();
        } catch (Exception ignored) {}
    }

    public static class Builder {
        private InfluxDB3Config influx3Config;
        private StorageConfig storageConfig;
        private String duckdbMaxMemory;

        public Builder influx3Config(InfluxDB3Config influx3Config) {
            this.influx3Config = influx3Config;
            return this;
        }

        public Builder localStorageDir(String baseDir) {
            if (baseDir != null && !baseDir.isBlank()) {
                this.storageConfig = LocalStorageConfig.of(baseDir);
            }
            return this;
        }

        public Builder storageConfig(StorageConfig storageConfig) {
            this.storageConfig = storageConfig;
            return this;
        }

        public Builder duckdbMaxMemory(String duckdbMaxMemory) {
            this.duckdbMaxMemory = duckdbMaxMemory;
            return this;
        }

        public Influx3xtendClient build() {
            Influx3xtendConfig config = new Influx3xtendConfig(influx3Config, storageConfig, duckdbMaxMemory);
            return new Influx3xtendClient(config);
        }
    }
}
