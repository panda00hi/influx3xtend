package com.influx3xtend.config;

import com.influxdb.v3.client.InfluxDBClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * InfluxDB 3 客户端自动配置类。
 * 根据 {@link InfluxDBProperties} 创建并配置 {@link com.influxdb.v3.client.InfluxDBClient} Bean。
 */
@Configuration
public class Influx3xtendAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(Influx3xtendAutoConfiguration.class);

    /**
     * 创建 InfluxDB v3 客户端 Bean。
     *
     * @param properties InfluxDB 配置属性
     * @return InfluxDBClient 实例
     */
    @Bean
    public InfluxDBClient influxDBClientV3(InfluxDBProperties properties) {
        String host = properties.getUrl();
        String token = properties.getToken();
        String database = properties.getDatabase();
        try {
            InfluxDBClient client = InfluxDBClient.getInstance(host, token != null ? token.toCharArray() : null, database);

            logger.info("InfluxDB v3 Client Bean created successfully.");
            return client;
        } catch (Exception e) {
            logger.error("Failed to create InfluxDB v3 Client Bean: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create InfluxDB v3 Client Bean", e);
        }
    }

}