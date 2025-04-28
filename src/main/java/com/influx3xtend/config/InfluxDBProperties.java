package com.influx3xtend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * InfluxDB配置属性类
 * 用于配置InfluxDB的连接参数
 */
@ConfigurationProperties(prefix = "influxdb")
public class InfluxDBProperties {

    /**
     * InfluxDB服务器URL
     */
    private String url = "http://localhost:18181";


    /**
     * InfluxDB访问令牌
     */
    private String token = "apiv3_TV_Hk_Sf0pC0Hew8oN0774hWu971HVMRChp10hTYK-LWk4gHW-z-igH9lhZztlq0cOIiQz-3HkRbYoF2AtKQhQ";

    /**
     * InfluxDB数据库/桶名称
     */
    private String database = "mydb";

    /**
     * Parquet文件目录
     */
    private String parquetDirectory = "./data/parquet";


    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getParquetDirectory() {
        return parquetDirectory;
    }

    public void setParquetDirectory(String parquetDirectory) {
        this.parquetDirectory = parquetDirectory;
    }

}