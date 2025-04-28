package com.influx3xtend.engine;

import com.alibaba.fastjson2.JSONObject;
import com.influx3xtend.model.TemperatureData;
import jakarta.annotation.Resource;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * @author panda00hi
 * @date 2025.04.28
 */
@SpringBootTest
class DefaultAnalysisEngineAdapterTest {


    @Resource
    DefaultAnalysisEngineAdapter defaultAnalysisEngineAdapter;

    @Test
    void executeQuery() {
        List<TemperatureData> dataList = defaultAnalysisEngineAdapter.executeQuery("SELECT * FROM temperature limit 10", TemperatureData.class);
        System.out.println(JSONObject.toJSONString(dataList));

    }

    @Test
    void writePoints() {
    }
}