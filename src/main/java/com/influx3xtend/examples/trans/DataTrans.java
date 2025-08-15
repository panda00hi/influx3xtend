package com.influx3xtend.examples.trans;

import com.influx3xtend.model.FeatureResult;
import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.Point;
import com.influxdb.v3.client.write.WritePrecision;
import io.questdb.client.Sender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author panda00hi
 * @date 2025.08.15
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DataTrans {

    private final MongoTemplate mongoTemplate;

    private final InfluxDBClient influxDBClient;

    /**
     * 从mongodb中读取数据并写入influxdb/questdb
     *
     * @param select 0-全部，1-influxdb 2-questdb
     * @throws InterruptedException 线程中断异常
     */
    public void process(int select) throws InterruptedException {
        // 先查总条数
        long a = System.currentTimeMillis();
        long total = mongoTemplate.count(new Query(), FeatureResult.class);
        long b = System.currentTimeMillis();
        System.out.println("MongoDB 总记录数 = " + total + " count耗时 = " + (b - a));
        // 分批处理，每次处理10000条，写入influxdb
        long processed = 0;
        Object lastId = null;
        final int BATCH = 10000;
        while (true) {
            Query query = new Query();
            if (lastId != null) {
                query.addCriteria(Criteria.where("_id").gt(lastId));
            }
            query.with(Sort.by(Sort.Direction.ASC, "_id"));
            query.limit(BATCH);
            long t1 = System.currentTimeMillis();
            List<FeatureResult> batch = mongoTemplate.find(query, FeatureResult.class);
            long t2 = System.currentTimeMillis();
            if (batch.isEmpty()) {
                break;
            }

            // todo 待优化，使用多线程分别写入
            long cost1 = 0;
            long cost2 = 0;
            if (select == 1) {
                cost1 = write2Influx(batch);
            } else if (select == 2) {

                cost2 = write2questDB(batch);
            } else if (select == 0) {
                cost1 = write2Influx(batch);
                cost2 = write2questDB(batch);
            } else {
                throw new IllegalArgumentException("参数错误");
            }
            processed += batch.size();
            lastId = batch.get(batch.size() - 1).getId();
            System.out.printf("finished %d / %d%n query cost %d batch: %d cost1: %d cost2: %d",
                    processed, total, (t2 - t1), batch.size(), cost1, cost2);
        }

    }

    private long write2Influx(List<FeatureResult> records) throws InterruptedException {
        List<Point> points = records.stream()
                .map(this::toPoint)
                .collect(Collectors.toList());
        long a = System.currentTimeMillis();
        influxDBClient.writePoints(points);
        long b = System.currentTimeMillis();
        return b - a;
    }

    private long write2questDB(List<FeatureResult> records) {
        long a = System.currentTimeMillis();
        try (Sender sender = Sender.builder(Sender.Transport.HTTP)
                .address("172.16.224.140:9000")
                .autoFlushRows(10000)
                .retryTimeoutMillis(10000)
                .build()) {

            for (FeatureResult record : records) {
                sender.table("feature_result")
                        .symbol("featureNo", record.getFeatureNo())
                        .stringColumn("id", record.getId())
                        .doubleColumn("value", record.getValue())
                        .at((Long) record.getKey(), ChronoUnit.MILLIS);
            }
        }
        long b = System.currentTimeMillis();
        return b - a;

    }

    private Point toPoint(FeatureResult featureResult) {
        return Point.measurement("feature_result")
                .setTag("featureNo", featureResult.getFeatureNo())
                .setField("id", featureResult.getId())
                .setField("value", featureResult.getValue())
                .setTimestamp(featureResult.getKey(), WritePrecision.MS);
    }


}
