package com.influx3xtend.engine;


import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.Point;
import com.influxdb.v3.client.PointValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
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

    /**
     * InfluxDB 3 官方客户端实例 (由Spring注入)
     */
    private final InfluxDBClient influxDBClient;
    private final String database;


    /**
     * 构造函数，注入InfluxDB 3客户端和数据库名称。
     *
     * @param influxDBClient InfluxDB 3 客户端实例 (由Spring容器管理)
     * @param database       要操作的数据库名称
     */
    public DefaultAnalysisEngineAdapter(InfluxDBClient influxDBClient, String database) {
        if (influxDBClient == null) {
            throw new IllegalArgumentException("InfluxDBClient cannot be null. Check Spring configuration.");
        }
        if (database == null || database.trim().isEmpty()) {
            throw new IllegalArgumentException("Database name cannot be null or empty.");
        }
        this.influxDBClient = influxDBClient;
        this.database = database;
        logger.info("DefaultAnalysisEngineAdapter initialized with injected InfluxDBClient for database '{}'.", database);
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
    public <T> List<T> executeQuery(String query, Class<T> resultType) {
        long startTime = System.currentTimeMillis();
        logger.info("Executing InfluxDB query against database '{}' and mapping to {}: {}", database, resultType.getSimpleName(), query);

        try (Stream<PointValues> stream = influxDBClient.queryPoints(query)) {
            List<T> resultList = stream.map(pointValues -> mapPointValuesToObject(pointValues, resultType))
                    .collect(Collectors.toList());

            long endTime = System.currentTimeMillis();
            logger.info("InfluxDB query executed successfully, returned {} rows mapped to {} cost:{}ms", resultList.size(), resultType.getSimpleName(), endTime - startTime);
            return resultList;
        } catch (Exception e) {
            logger.error("Failed to execute InfluxDB query against database '{}' and map to {}: {}", database, resultType.getSimpleName(), query, e);
            throw new RuntimeException("InfluxDB query execution failed: " + e.getMessage(), e);
        }
    }


    private <T> T mapPointValuesToObject(PointValues pointValues, Class<T> resultType) {
        try {
            T instance = resultType.getDeclaredConstructor().newInstance();

            Map<String, Object> colMapping = new HashMap<>();
            assert pointValues.getTimestamp() != null;
            colMapping.put("time", pointValues.getTimestamp().longValue());
            for (String tagName : pointValues.getTagNames()) {
                String colName = tagName.toLowerCase();
                colMapping.put(colName, pointValues.getTag(tagName));
            }
            for (String fieldName : pointValues.getFieldNames()) {
                String colName = fieldName.toLowerCase();
                colMapping.put(colName, pointValues.getField(fieldName));
            }

            // 获取所有字段，包括父类的字段
            Field[] fields = getAllFields(resultType);
            for (Field field : fields) {
                field.setAccessible(true);
                String fieldName = field.getName();
                Object value = colMapping.get(fieldName);
                if (value != null) {
                    if ("time".equals(fieldName)) {
                        field.set(instance, new Timestamp((Long) value));
                    } else {
                        field.set(instance, value);
                    }
                }
            }

            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to map PointValues to " + resultType.getSimpleName(), e);
        }
    }


    /**
     * 使用 InfluxDB 3 的写入 API 写入数据点。
     *
     * @param points 要写入的数据点列表
     * @throws RuntimeException 如果写入失败
     */
    @Override
    public void writePoints(List<Point> points) {
        if (points == null || points.isEmpty()) {
            logger.warn("No points provided for writing.");
            return;
        }
        logger.info("Writing {} points to database '{}'", points.size(), database);
        try {
            // 使用默认写入选项
            influxDBClient.writePoints(points);
            logger.info("Successfully wrote {} points.", points.size());
        } catch (Exception e) {
            logger.error("Failed to write points to database '{}'", database, e);
            throw new RuntimeException("Failed to write points: " + e.getMessage(), e);
        }
    }

}