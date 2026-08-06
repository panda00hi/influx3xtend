package org.influx3xtend.core.config;

import org.influx3xtend.core.constants.Influx3xtendDefaults;

import java.util.Objects;

/**
 * Influx3xtend 中间件全局配置对象
 */
public record Influx3xtendConfig(
        InfluxDB3Config influx3Config,
        StorageConfig storageConfig,
        String duckdbMaxMemory
) {
    public Influx3xtendConfig {
        Objects.requireNonNull(influx3Config, "InfluxDB3Config cannot be null");
        duckdbMaxMemory = Influx3xtendDefaults.getDuckdbMaxMemory(duckdbMaxMemory);
    }
}
