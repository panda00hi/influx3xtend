package com.influx3xtend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * InfluxDB配置属性类
 * 用于配置InfluxDB的连接参数
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "duckdb")
public class DuckDBProperties {

    /**
     * InfluxDB服务器URL
     */
    private String url = "http://172.16.224.140:8181";


    /**
     * InfluxDB访问令牌
     */
    private String token = "apiv3_LWTnyE7ggfmGj88HWaGXO3r2dbqZNCLYN8gNyacJ-ZXt4EaZsVvz6lRpwUf4JCozRCIzyE1Q5DVz_5CfdSJXxw";

    /**
     * InfluxDB数据库/桶名称
     */
    private String database = "xtend-test";

    /**
     * Parquet文件目录
     */
    private String parquetDirectory = "./data/parquet";




}