package com.influx3xtend.engine;

import com.alibaba.fastjson2.JSONObject;
import com.influx3xtend.model.FeatureResult;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author panda00hi
 * @date 2025.04.28
 */
@SpringBootTest
class DuckDBAnalysisEngineAdapterTest {


    @Resource
    DuckDBAnalysisEngineAdapter duckDBAnalysisEngineAdapter;


    @Test
    void executeQuery() {
        String database = "algo";
        String measurement = "feature_result";
        String querySql = duckDBAnalysisEngineAdapter.buildQuery(database, measurement, null, null);

        List<FeatureResult> points = duckDBAnalysisEngineAdapter.executeQuery(querySql, FeatureResult.class);
        points.forEach(point ->
                System.out.println(JSONObject.toJSONString(point)));
        System.out.printf("查询结果：%d 条%n", points.size());

    }

    @Test
    void executeQueryByTimeRange() {
        String database = "algo";
        String measurement = "feature_result";
        LocalDateTime startTime = LocalDateTime.of(2025, 8, 2, 23, 0, 0);
        LocalDateTime endTime = startTime.plusHours(1);

        String querySql = duckDBAnalysisEngineAdapter.buildQuery(database, measurement, startTime, endTime);

        List<FeatureResult> points = duckDBAnalysisEngineAdapter.executeQuery(querySql, FeatureResult.class);
        points.forEach(point ->
                System.out.println(JSONObject.toJSONString(point)));
        System.out.printf("查询结果：%d 条  查询范围： {%s}-{%s} %n", points.size(), startTime, endTime);

    }

}