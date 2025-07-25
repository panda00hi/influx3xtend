package com.influx3xtend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 分析引擎配置
 * 默认使用DuckDB
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "engine")
public class EngineProperties {
    private boolean enabled = true;
    private String type = "duckdb";
    private DuckDBProperties duckdb = new DuckDBProperties();

    @Data
    public static class DuckDBProperties {

        private boolean readOnly = true;

        /**
         * Parquet文件目录
         */
        private String parquetDirectory;
    }


}