package org.influx3xtend.example.config;

import org.influx3xtend.core.api.Influx3xtendClient;
import org.influx3xtend.core.config.InfluxDB3Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 3 中间件配置类：注册 Influx3xtendClient Bean (模式一集成)
 */
@Configuration
public class EdgeInflux3xtendConfig {

    private static final Logger log = LoggerFactory.getLogger(EdgeInflux3xtendConfig.class);

    @Bean(destroyMethod = "close")
    public Influx3xtendClient influx3xtendClient(
            @Value("${influx3xtend.influx3.host:http://localhost:8181}") String host,
            @Value("${influx3xtend.influx3.token:default-token}") String token,
            @Value("${influx3xtend.influx3.database:edge_iot_db}") String database,
            @Value("${influx3xtend.storage.local-storage-dir:target/edge_parquet_storage}") String localStorageDir,
            @Value("${influx3xtend.duckdb-max-memory:2GB}") String duckdbMaxMemory) {

        log.info("Initializing Influx3xtendClient for Spring Boot Edge IoT Application...");
        log.info("InfluxDB 3 Host: {}, Database: {}, Local Parquet Storage Dir: {}", host, database, localStorageDir);

        InfluxDB3Config influx3Config = InfluxDB3Config.builder()
                .host(host)
                .token(token)
                .database(database)
                .build();

        return Influx3xtendClient.builder()
                .influx3Config(influx3Config)
                .localStorageDir(localStorageDir)
                .duckdbMaxMemory(duckdbMaxMemory)
                .build();
    }
}
