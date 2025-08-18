package com.influx3xtend.engine;


import com.influx3xtend.annotation.XTime;
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
import java.sql.Timestamp;
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
    private final String DATABASE_NAME;

    /**
     * 构造函数。
     */
    public DuckDBAnalysisEngineAdapter(String parquetDir, String databaseName) throws SQLException {
        this.duckdbConnection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        PARQUET_BASE_DIR = parquetDir;
        DATABASE_NAME = databaseName;
        logger.info("DuckDBAnalysisEngineAdapter initialized with Parquet directory: {} dataBase: {}", PARQUET_BASE_DIR, DATABASE_NAME);
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
            // 获取所有字段，包括父类的字段
            Field[] fields = getAllFields(resultType);

            while (resultSet.next()) {
                T instance = resultType.getDeclaredConstructor().newInstance();
                for (Field field : fields) {
                    field.setAccessible(true);
                    if (field.isAnnotationPresent(XTime.class)) {
                        Integer index = columnIndexMap.get("time");
                        if (index != null) {
                            setTimeField(field, resultSet, index, instance);
                        }
                    } else {
                        String fieldName = field.getName();
                        Integer index = columnIndexMap.get(fieldName.toLowerCase());
                        if (index != null) {
                            Object object = resultSet.getObject(index);
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
     * 设置时间字段
     */
    private void setTimeField(Field field, ResultSet resultSet, Integer index, Object instance) throws SQLException, IllegalAccessException {
        if (index == null) return;
        Object object = resultSet.getObject(index);
        if (object instanceof Timestamp) {
            long timeInMillis = ((Timestamp) object).getTime();
            XTime xTimeAnnotation = field.getAnnotation(XTime.class);
            object = switch (xTimeAnnotation.value()) {
                case SECONDS -> timeInMillis / 1000;
                case MICROSECONDS -> timeInMillis * 1000;
                case NANOSECONDS -> timeInMillis * 1000_000;
                default -> timeInMillis;
            };
        }
        field.set(instance, object);
    }


    /**
     * 构建查询SQL
     * 若无时间范围，默认1000条
     */
    public String buildQuery(String database, String table, LocalDateTime startTime, LocalDateTime endTime) {
        // 校验数据文件是否存在
        // 检查PARQUET_BASE_DIR下是否存在database开头的目录
        File[] files = new File(PARQUET_BASE_DIR).listFiles(file -> file.isDirectory() && file.getName().startsWith(database));
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("Parquet directory or database does not exist: " + PARQUET_BASE_DIR + "/" + database);
        }
        String dbPath = files[0].getAbsolutePath();
        File[] tableFiles = new File(dbPath).listFiles(file -> file.isDirectory() && file.getName().startsWith(table));
        if (tableFiles == null || tableFiles.length == 0) {
            throw new IllegalArgumentException("Parquet table files does not exist: " + PARQUET_BASE_DIR + "/" + dbPath + "/" + table);
        }
        String tablePath = tableFiles[0].getAbsolutePath();


        List<String> paths = List.of();
        if (startTime != null && endTime != null) {
            paths = collectDirsByDateRange(tablePath, startTime.toLocalDate(), endTime.toLocalDate());
        } else {
            paths = List.of(dbPath + File.separator + table + "-*");
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
                    .append(" AND time < TO_TIMESTAMP(").append(end).append(")")
                    .append(" ORDER BY time");
        } else {
            sql.append(" ORDER BY time LIMIT 10000");
        }

        logger.info("Generated DuckDB SQL: {}", sql);
        return sql.toString();
    }

    /**
     * 根据时间范围收集目录
     */
    public List<String> collectDirsByDateRange(String tablePath, LocalDate startDate, LocalDate endDate) {
        List<String> dirs = new ArrayList<>();
        LocalDate date = startDate;
        while (!date.isAfter(endDate)) {
            // 注意：由于官方对数据逻辑删除等处理，持久化部分实际的数据库/表的后缀，不一定是“-1”，因此此处使用通配符匹配所有
            String datePath = String.format("%s/%s", tablePath, date);
            // 目录不存在，可能目录错误或还未完成持久化parquet
            if (!new File(datePath).exists()) {
                logger.warn("no directory: {}", datePath);
                date = date.plusDays(1);
                continue;
            }
            dirs.add(datePath);
            date = date.plusDays(1);
        }
        return dirs;
    }


}