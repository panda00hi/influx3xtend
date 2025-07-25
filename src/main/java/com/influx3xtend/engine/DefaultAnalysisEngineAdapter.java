package com.influx3xtend.engine;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.influx3xtend.config.InfluxDBProperties;
import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.Point;
import com.influxdb.v3.client.PointValues;
import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 默认分析引擎适配器实现，使用官方 `influxdb3-java` 与 InfluxDB 3 Core 交互。
 */
@Service
public class DefaultAnalysisEngineAdapter implements AnalysisEngineAdapter {
    private static final Logger logger = LoggerFactory.getLogger(DefaultAnalysisEngineAdapter.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * InfluxDB 3 官方客户端实例 (由Spring注入)
     */
    private final InfluxDBClient influxDBClient;
    private final String database;


    /**
     * 构造函数，注入InfluxDB 3客户端和数据库名称。
     *
     * @param influxDBClient     InfluxDB 3 客户端实例 (由Spring容器管理)
     * @param influxDBProperties 配置
     */
    public DefaultAnalysisEngineAdapter(InfluxDBClient influxDBClient, InfluxDBProperties influxDBProperties) {
        if (influxDBClient == null) {
            throw new IllegalArgumentException("InfluxDBClient cannot be null. Check Spring configuration.");
        }
        this.influxDBClient = influxDBClient;
        this.database = influxDBProperties.getDatabase();
        logger.info("DefaultAnalysisEngineAdapter initialized with injected InfluxDBClient for database '{}'.", influxDBProperties);
    }

    private <T> T mapPointValuesToObject(PointValues pointValues, Class<T> resultType) {
        try {
            // 构建原始字段数据（保留原始字段名，不转小写）
            Map<String, Object> dataMap = new HashMap<>();
            assert pointValues.getTimestamp() != null;
            dataMap.put("time", new Timestamp(pointValues.getTimestamp().longValue()));

            for (String tagName : pointValues.getTagNames()) {
                dataMap.put(tagName, pointValues.getTag(tagName));
            }
            for (String fieldName : pointValues.getFieldNames()) {
                dataMap.put(fieldName, pointValues.getField(fieldName));
            }
            // 使用 Jackson 自动映射字段（支持下划线转驼峰）
            return objectMapper.convertValue(dataMap, resultType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to map PointValues to " + resultType.getSimpleName(), e);
        }
    }

    /**
     * 执行 SQL 查询，并将结果映射到指定类型的对象列表。
     *
     * @param query      SQL 查询语句
     * @param resultType 查询结果要映射到的 POJO 类
     * @param <T>        结果对象的类型
     * @return 查询结果列表，每个元素是 T 类型的实例
     * @throws RuntimeException 如果查询执行失败
     */
    @Override
    public <T> List<T> executeQuery(@Nonnull String query, @Nonnull Class<T> resultType) {
        long startTime = System.currentTimeMillis();
        logger.debug("Executing InfluxDB query against database '{}' and mapping to {}: {}", database, resultType.getSimpleName(), query);

        try (Stream<PointValues> stream = influxDBClient.queryPoints(query)) {
            List<T> resultList = stream.map(pointValues -> mapPointValuesToObject(pointValues, resultType))
                    .collect(Collectors.toList());

            long endTime = System.currentTimeMillis();
            logger.debug("InfluxDB query executed successfully, returned {} rows mapped to {} cost:{}ms", resultList.size(), resultType.getSimpleName(), endTime - startTime);
            return resultList;
        } catch (Exception e) {
            logger.error("Failed to execute InfluxDB query against database '{}' and map to {}: {}", database, resultType.getSimpleName(), query, e);
            throw new RuntimeException("InfluxDB query execution failed: " + e.getMessage(), e);
        }
    }


    /**
     * 使用 InfluxDB 3 的写入 API 写入数据点。
     *
     * @param points 要写入的数据点列表
     * @throws RuntimeException 如果写入失败
     */
    @Override
    public void writePoints(@Nonnull List<Point> points) {
        if (points.isEmpty()) {
            logger.warn("No points provided for writing.");
            return;
        }
        logger.debug("Writing {} points to database '{}'", points.size(), database);
        try {
            // 使用默认写入选项
            influxDBClient.writePoints(points);
            logger.debug("Successfully wrote {} points.", points.size());
        } catch (Exception e) {
            logger.error("Failed to write points to database '{}'", database, e);
            throw new RuntimeException("Failed to write points: " + e.getMessage(), e);
        }
    }
}