package com.influx3xtend.engine;

import com.influxdb.v3.client.Point;
import jakarta.annotation.Nonnull;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 分析引擎适配器接口，定义与分析引擎集成的核心操作，专注于轻量级的连接和查询功能。
 * 简化设计，便于与InfluxDB 3 Core的Parquet文件进行历史数据查询集成。
 */
public interface AnalysisEngineAdapter {
    /**
     * 执行分析引擎的查询操作并将结果映射到指定类型的对象列表。
     *
     * @param query      查询语句 (SQL)
     * @param resultType 查询结果要映射到的 POJO 类
     * @param <T>        结果对象的类型
     * @return 查询结果列表，每个元素是 T 类型的实例
     */
    <T> List<T> executeQuery(@Nonnull String query, @Nonnull Class<T> resultType);

    /**
     * 使用 InfluxDB 3 的写入 API 写入数据点。
     *
     * @param points 要写入的数据点列表
     * @throws RuntimeException 如果写入失败
     */
    void writePoints(@Nonnull List<Point> points);


    default Field[] getAllFields(Class<?> clazz) {
        if (clazz == null) {
            return new Field[0];
        }

        Field[] declaredFields = clazz.getDeclaredFields();
        Field[] parentFields = getAllFields(clazz.getSuperclass());

        Field[] allFields = new Field[declaredFields.length + parentFields.length];
        System.arraycopy(declaredFields, 0, allFields, 0, declaredFields.length);
        System.arraycopy(parentFields, 0, allFields, declaredFields.length, parentFields.length);

        return allFields;
    }


}