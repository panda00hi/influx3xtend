package com.influx3xtend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Properties;

/**
 * 分析引擎配置属性类
 * 用于配置分析引擎的类型和连接参数
 * 支持Spring Boot自动配置
 */
@ConfigurationProperties(prefix = "influx3xtend.engine")
public class EngineProperties {
    
    /**
     * 引擎类型，如duckdb、default等
     */
    private String type = "default";
    
    /**
     * 连接超时时间（毫秒）
     */
    private long connectionTimeout = 5000;
    
    /**
     * 查询超时时间（毫秒）
     */
    private long queryTimeout = 30000;
    
    /**
     * 是否自动连接
     */
    private boolean autoConnect = true;
    
    /**
     * 查询路由时间阈值
     * 默认72小时，超过此时间的查询将路由到历史数据引擎
     */
    private Duration routingThreshold = Duration.ofHours(72);
    
    /**
     * 引擎连接属性
     */
    private Properties properties = new Properties();
    
    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
    }
    
    public Properties getProperties() {
        return properties;
    }
    
    public void setProperties(Properties properties) {
        this.properties = properties;
    }
    
    public long getConnectionTimeout() {
        return connectionTimeout;
    }
    
    public void setConnectionTimeout(long connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }
    
    public long getQueryTimeout() {
        return queryTimeout;
    }
    
    public void setQueryTimeout(long queryTimeout) {
        this.queryTimeout = queryTimeout;
    }
    
    public boolean isAutoConnect() {
        return autoConnect;
    }
    
    public void setAutoConnect(boolean autoConnect) {
        this.autoConnect = autoConnect;
    }
    
    public Duration getRoutingThreshold() {
        return routingThreshold;
    }
    
    public void setRoutingThreshold(Duration routingThreshold) {
        this.routingThreshold = routingThreshold;
    }
}