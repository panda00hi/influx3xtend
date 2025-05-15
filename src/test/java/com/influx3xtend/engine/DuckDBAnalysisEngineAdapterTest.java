package com.influx3xtend.engine;

import com.alibaba.fastjson2.JSONObject;
import com.influx3xtend.model.TemperatureData;
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

        LocalDateTime startTime = LocalDateTime.of(2025, 4, 19, 0, 0, 0);
        LocalDateTime endTime = startTime.plusDays(1);

        String querySql = DuckDBAnalysisEngineAdapter.buildQuery("temperature", startTime, endTime);
        // String querySql = DuckDBAnalysisEngineAdapter.buildQuery("temperature", null, null);

        List<TemperatureData> points = duckDBAnalysisEngineAdapter.executeQuery(querySql, TemperatureData.class);
        // System.out.println(JSONObject.toJSONString(points));
        points.forEach(point ->
                System.out.println(JSONObject.toJSONString(point)));

    }

    @Test
    void writePoints() {
    }
}