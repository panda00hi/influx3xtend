package org.influx3xtend.core.config;

import org.influx3xtend.core.constants.Influx3xtendDefaults;

import java.time.Duration;
import java.util.Objects;

/**
 * InfluxDB 3 官方 Client 连接配置与限制视窗定义
 */
public record InfluxDB3Config(
        String host,
        String token,
        String database,
        String organization,
        Duration maxNativeQueryWindow
) {
    public InfluxDB3Config {
        Objects.requireNonNull(host, "InfluxDB 3 host cannot be null");
        Objects.requireNonNull(token, "InfluxDB 3 token cannot be null");
        Objects.requireNonNull(database, "InfluxDB 3 database cannot be null");
        maxNativeQueryWindow = Influx3xtendDefaults.getMaxNativeQueryWindow(maxNativeQueryWindow);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String host;
        private String token;
        private String database;
        private String organization;
        private Duration maxNativeQueryWindow;

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder token(String token) {
            this.token = token;
            return this;
        }

        public Builder database(String database) {
            this.database = database;
            return this;
        }

        public Builder organization(String organization) {
            this.organization = organization;
            return this;
        }

        public Builder maxNativeQueryWindow(Duration maxNativeQueryWindow) {
            this.maxNativeQueryWindow = maxNativeQueryWindow;
            return this;
        }

        public InfluxDB3Config build() {
            return new InfluxDB3Config(host, token, database, organization, maxNativeQueryWindow);
        }
    }
}
