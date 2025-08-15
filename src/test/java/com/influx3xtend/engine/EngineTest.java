package com.influx3xtend.engine;

import com.alibaba.fastjson2.JSONObject;
import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.Point;
import com.influxdb.v3.client.PointValues;
import com.influxdb.v3.client.write.WritePrecision;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * @author panda00hi
 * @date 2025.06.25
 */
@Slf4j
@SpringBootTest
public class EngineTest {

    private static final String database = "test07";
    private static final String host = "http://172.16.224.140:8181";
    private static final String token = "apiv3_w-9sdjqe780PfnT5KT0nHaRqDtk8UEze6sMIzEkkvsd273Y1J5QYWQC-cV33ToZ1nb8cUs_INTcABJAgcGMuCQ";

    @Test
    void writeTs() throws InterruptedException {
        InfluxDBClient client = InfluxDBClient.getInstance(host, token.toCharArray(), database);
        LocalDateTime now = LocalDateTime.now();
        long ts = now.toInstant(ZoneOffset.of("+8")).toEpochMilli() * 1000 + 1;
        Point point1 = Point.measurement("special")
                .setTags(Map.of("sn", "SPEC20240601", "model", "10kHz", "name", "电流监测仪"))
                .setField("U", Math.random() * 100)
                .setField("V", Math.random() * 80)
                .setField("W", Math.random() * 60)
                .setTimestamp(ts, WritePrecision.US);
        System.out.println(JSONObject.toJSONString(point1));
        client.writePoint(point1);

        long ts2 = now.minusDays(5).toInstant(ZoneOffset.of("+8")).toEpochMilli() + 1;
        Point point2 = Point.measurement("special2")
                .setTags(Map.of("sn", "SPEC20240601", "model", "1Hz", "name", "电流监测仪"))
                .setField("U", Math.random() * 100)
                .setField("V", Math.random() * 80)
                .setField("W", Math.random() * 60)
                .setTimestamp(ts2, WritePrecision.MS);
        System.out.println(JSONObject.toJSONString(point2));
        client.writePoint(point2);

        // client.writePoints(List.of(point1, point2));
        System.out.println("=====Writing finished=======");

        Thread.sleep(1000);
        client.queryPoints("SELECT * FROM special2").forEach(pointValues -> {
            System.out.println(JSONObject.toJSONString(pointValues));
        });

        String query = "SELECT * FROM special2 limit 10";
        Stream<PointValues> stream = client.queryPoints(query);
        List<SensorData> resultList = stream
                .map(pointValues -> mapPointValuesToObject(pointValues, SensorData.class))
                .toList();
        System.out.println(JSONObject.toJSONString(resultList));


    }


    @Test
    void writePoints() throws InterruptedException {
        InfluxDBClient client = InfluxDBClient.getInstance(host, token.toCharArray(), database);

        LocalDateTime now = LocalDateTime.now();

        // 只生成6月份开始的数据
        LocalDateTime startTime = LocalDateTime.of(2025, 6, 1, 0, 0);
        long startMill = startTime.toInstant(ZoneOffset.of("+8")).toEpochMilli();

        System.out.println("================低频1Hz数据，共3天===================");
        // 低频历史数据，3天
        for (int i = 0; i < 24 * 3; i++) {
            long currentMill = startMill + 3600 * i;
            List<Point> points = genData(currentMill, currentMill + 3600_000, 1, "history",
                    Map.of("sn", "20240601", "model", "1Hz", "name", "电流监测仪"));
            client.writePoints(points);
            System.out.println("Writing " + points.size() + " points...");
        }

        // // 低频近期数据72小时内
        // System.out.println("================低频近期数据72小时内===================");
        // long recentMill = now.minusHours(72).toInstant(ZoneOffset.of("+8")).toEpochMilli();
        //
        // for (int i = 0; i < 72; i++) {
        //     long currentMill = recentMill + 3600 * i;
        //     List<Point> points = genData(currentMill, currentMill + 3600_000, 1, "low_sensor",
        //             Map.of("sn", "20240601", "model", "1Hz", "name", "电流监测仪"));
        //     client.writePoints(points);
        //     System.out.println("Writing " + points.size() + " points...");
        // }

        // 当前实时高频数据
        System.out.println("================高频实时，60秒===================");
        long currentTimeMillis = now.toInstant(ZoneOffset.of("+8")).toEpochMilli();
        // 10秒
        for (int i = 0; i < 10; i++) {
            List<Point> points = genData(currentTimeMillis, currentTimeMillis + 1000, 10_000, "high_sensor",
                    Map.of("sn", "20250601", "model", "10Hz", "name", "高频电流监测仪"));
            client.writePoints(points);
            System.out.println("Writing " + points.size() + " points...");
        }

    }

    private List<Point> genData(long startMill, long endMill, int freq, String measurement, Map<String, String> tags) {
        List<Point> points = new ArrayList<>();
        long intervalUs = 1000_000 / freq;

        for (long timestamp = startMill * 1000; timestamp < endMill * 1000; timestamp += intervalUs) {
            WritePrecision precision = freq <= 1000 ? WritePrecision.MS : WritePrecision.US;
            long ts = freq <= 1000 ? timestamp / 1000 : timestamp;
            // 每秒生成一个数据点
            Point point = Point.measurement(measurement)
                    .setTags(tags)
                    .setField("U", Math.random() * 100)
                    .setField("V", Math.random() * 80)
                    .setField("W", Math.random() * 60)
                    .setTimestamp(ts, precision);
            points.add(point);
        }
        return points;
    }


    @Test
    void query() {
        InfluxDBClient influxDBClient = InfluxDBClient.getInstance(host, token.toCharArray(), database);

        long startTime = System.currentTimeMillis();
        String query = "SELECT * FROM high_sensor limit 10";
        Stream<PointValues> stream = influxDBClient.queryPoints(query);
        List<SensorData> resultList = stream
                .map(pointValues -> mapPointValuesToObject(pointValues, SensorData.class))
                .toList();

        long endTime = System.currentTimeMillis();
        System.out.println(JSONObject.toJSONString(resultList.get(0)));
        System.out.println("count: " + resultList.size() + "cost: " + (endTime - startTime));


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
                    field.set(instance, value);
                }
            }

            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to map PointValues to " + resultType.getSimpleName(), e);
        }
    }

    public Field[] getAllFields(Class<?> clazz) {
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


    @Data
    public static class SensorData {
        private Double u;
        private Double v;
        private Double w;
        private String sn;
        private String model;
        private String name;
        private long time;
    }
}
