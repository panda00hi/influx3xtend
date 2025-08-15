package com.influx3xtend.engine;

import com.influx3xtend.examples.trans.DataTrans;
import com.influx3xtend.model.FeatureResult;
import com.influxdb.v3.client.Point;
import com.influxdb.v3.client.write.WritePrecision;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * @author panda00hi
 * @date 2025.08.15
 */
@Slf4j
// 新起一个端口，不影响现在还未跑完的测试
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class DataTransTest {

    @Resource
    private MongoTemplate mongoTemplate;

    @Resource
    private DataTrans dataTrans;

    // 测试
    @Test
    public void test() {

        FeatureResult featureResult = mongoTemplate.findOne(new Query().limit(1), FeatureResult.class);
        log.info("featureResult = {}", featureResult);

        var point = Point.measurement("feature_result")
                .setTag("featureNo", featureResult.getFeatureNo())
                .setField("id", featureResult.getId())
                .setField("value", featureResult.getValue())
                .setTimestamp(featureResult.getKey(), WritePrecision.MS);

        log.info("point = {}", point);
    }


    @Test
    public void syncData() throws InterruptedException {
        System.out.println("开始同步数据...");
        long startTime = System.currentTimeMillis();
        dataTrans.process(1);
        System.out.println("同步数据完成！耗时：" + (System.currentTimeMillis() - startTime));
    }
}
