package com.influx3xtend.config;

import com.influxdb.v3.client.InfluxDBClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * InfluxDB 3 客户端自动配置类。
 * 根据 {@link InfluxDBProperties} 创建并配置 {@link com.influxdb.v3.client.InfluxDBClient} Bean。
 */
@Configuration
@EnableConfigurationProperties(InfluxDBProperties.class)
public class InfluxDBAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(InfluxDBAutoConfiguration.class);

    /**
     * 创建 InfluxDB v3 客户端 Bean。
     *
     * @param properties InfluxDB 配置属性
     * @return InfluxDBClient 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public InfluxDBClient influxDBClientV3(InfluxDBProperties properties) {
        String host = properties.getUrl();
        String token = properties.getToken();
        String database = properties.getDatabase();

        if (host == null || host.trim().isEmpty()) {
            logger.error("InfluxDB host URL (influx3xtend.influxdb.url) is not configured.");
            throw new IllegalStateException("InfluxDB host URL is required.");
        }
        if (token == null || token.trim().isEmpty()) {
            logger.warn("InfluxDB token (influx3xtend.influxdb.token) is not configured. Connection might fail if authentication is required.");
        }
        if (database == null || database.trim().isEmpty()) {
            logger.warn("InfluxDB database (influx3xtend.influxdb.database) is not configured. Operations might require specifying it explicitly.");
        }

        logger.info("Creating InfluxDB v3 Client Bean with host: {}, database: {}", host, database);

        try {
            InfluxDBClient client = InfluxDBClient.getInstance(host, token != null ? token.toCharArray() : null, database);

            logger.info("InfluxDB v3 Client Bean created successfully.");
            return client;
        } catch (Exception e) {
            logger.error("Failed to create InfluxDB v3 Client Bean: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create InfluxDB v3 Client Bean", e);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public String getDatabase(InfluxDBProperties properties) {
        return properties.getDatabase();
    }
}