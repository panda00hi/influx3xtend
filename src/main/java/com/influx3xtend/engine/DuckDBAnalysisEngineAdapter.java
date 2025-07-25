package com.influx3xtend.engine;


import com.influxdb.v3.client.Point;
import jakarta.annotation.Nonnull;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DuckDB 分析引擎适配器实现，专注于利用DuckDB读取InfluxDB 3 Core的Parquet文件进行历史数据查询。
 * 重构后的版本简化了实现，直接使用DuckDB JDBC驱动执行SQL查询，
 * 利用 `read_parquet` 函数动态读取指定目录下的Parquet文件。
 * 移除了复杂的组件组合和不必要的功能，使其更轻量。
 */
@Service
public class DuckDBAnalysisEngineAdapter implements AnalysisEngineAdapter {

    private static final Logger logger = LoggerFactory.getLogger(DuckDBAnalysisEngineAdapter.class);
    /**
     * DuckDB 连接实例，默认只读
     */
    private final DuckDBConnection duckdbConnection;
    private final String PARQUET_BASE_DIR;

    /**
     * 构造函数。
     */
    public DuckDBAnalysisEngineAdapter(String parquetDir) throws SQLException {
        this.duckdbConnection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        PARQUET_BASE_DIR = parquetDir;
        logger.info("DuckDBAnalysisEngineAdapter initialized with Parquet directory: {}", PARQUET_BASE_DIR);
    }

    /**
     * DuckDB 适配器不支持写入操作。
     *
     * @param points 要写入的数据点列表 (未使用)
     * @throws UnsupportedOperationException 始终抛出此异常
     */
    @Override
    public void writePoints(@Nonnull List<Point> points) {
        throw new UnsupportedOperationException("DuckDBAnalysisEngineAdapter does not support writing data points.");
    }

    /**
     * 执行分析引擎的查询操作并将结果映射到指定类型的对象列表。
     *
     * @param query      查询语句 (SQL)
     * @param resultType 查询结果要映射到的 POJO 类
     * @return 查询结果列表，每个元素是 T 类型的实例
     */
    @Override
    public <T> List<T> executeQuery(@Nonnull String query, @Nonnull Class<T> resultType) {
        if (query.trim().isEmpty()) {
            throw new IllegalArgumentException("Query string cannot be null or empty.");
        }

        long startTime = System.currentTimeMillis();
        logger.info("Executing DuckDB query: {}", query);

        try (DuckDBConnection connection = duckdbConnection;
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {

            List<T> resultList = new ArrayList<>();
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();

            Map<String, Integer> columnIndexMap = new HashMap<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                columnIndexMap.put(metaData.getColumnLabel(i).toLowerCase(), i);
            }

            while (resultSet.next()) {
                T instance = resultType.getDeclaredConstructor().newInstance();
                // 获取所有字段，包括父类的字段
                Field[] fields = getAllFields(resultType);
                for (Field field : fields) {
                    field.setAccessible(true);
                    String fieldName = field.getName();
                    Integer index = columnIndexMap.get(fieldName);
                    if (index != null) {
                        Object object = resultSet.getObject(index);
                        if (object != null) {
                            field.setAccessible(true);
                            field.set(instance, object);
                        }
                    }
                }
                resultList.add(instance);
            }

            long endTime = System.currentTimeMillis();
            logger.debug("DuckDB query executed successfully, returned {} rows, cost: {}ms", resultList.size(), endTime - startTime);
            return resultList;

        } catch (SQLException e) {
            logger.error("Failed to execute DuckDB query: {}, error: {}", query, e.getMessage(), e);
            throw new RuntimeException("DuckDB query execution failed: " + e.getMessage(), e);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException |
                 InvocationTargetException e) {
            logger.error("Failed to map query result to object: {}, error: {}", query, e.getMessage(), e);
            throw new RuntimeException("Failed to map query result to object: " + e.getMessage(), e);
        }
    }


    /**
     * 构建查询SQL
     * 若无时间范围，默认1000条
     */
    public String buildQuery(String table, LocalDateTime startTime, LocalDateTime endTime) {
        List<String> paths = List.of();
        if (startTime != null && endTime != null) {
            paths = collectDirsByDateRange(table, startTime.toLocalDate(), endTime.toLocalDate());
        } else {
            paths = List.of(PARQUET_BASE_DIR + table + "-1");
            // 校验待查询表的目录存在
            if (!new File(paths.get(0)).exists()) {
                throw new IllegalArgumentException("Parquet directory does not exist: " + paths.get(0));
            }
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM parquet_scan([")
                .append(
                        paths.stream()
                                .map(p -> "'" + p + "/**/*.parquet'")
                                .collect(Collectors.joining(", ")))
                .append("])");

        if (startTime != null && endTime != null) {
            // 注意：duckDB目前只支持到秒级。所以要转秒时间戳，系统默认时区
            long start = startTime.atZone(ZoneId.systemDefault()).toEpochSecond();
            long end = endTime.atZone(ZoneId.systemDefault()).toEpochSecond();
            // 使用 TO_TIMESTAMP 函数进行转换
            sql.append(" WHERE time >= TO_TIMESTAMP(").append(start).append(")")
                    .append(" AND time <= TO_TIMESTAMP(").append(end).append(")");
        } else {
            sql.append(" ORDER BY time DESC LIMIT 1000");
        }

        logger.info("Generated DuckDB SQL: {}", sql);
        return sql.toString();
    }

    /**
     * 根据时间范围收集目录
     */
    public List<String> collectDirsByDateRange(String table, LocalDate startDate, LocalDate endDate) {
        List<String> dirs = new ArrayList<>();
        LocalDate date = startDate;
        while (!date.isAfter(endDate)) {
            String dir = String.format("%s/%s-1/%s", PARQUET_BASE_DIR, table, date);
            // 目录不存在，可能目录错误或还未完成持久化parquet
            if (!new File(dir).exists()) {
                logger.debug("Found directory: {}", dir);
            }
            dirs.add(dir);
            date = date.plusDays(1);
        }
        return dirs;
    }


}