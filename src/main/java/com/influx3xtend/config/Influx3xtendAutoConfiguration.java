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

    @Bean
    public String getParquetDir(InfluxDBProperties properties, EngineProperties engineProperties) {
        // 若启用分析引擎且指定了parquet目录，则返回该目录；否则返回influxdb3的parquet-dir目录
        // 用途：支持用户自定义进行了数据同步操作，如将influxdb原本的数据文件，同步到其他位置，此时需要自行指定parquet目录
        if (engineProperties.isEnabled() && engineProperties.getDuckdb().getParquetDirectory() != null) {
            return engineProperties.getDuckdb().getParquetDirectory();
        }
        return properties.getParquetDir();
    }


}