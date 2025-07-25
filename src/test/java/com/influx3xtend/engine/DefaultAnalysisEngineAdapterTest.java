package com.influx3xtend.engine;

import com.influx3xtend.model.WeatherData;
import com.influxdb.v3.client.Point;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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
        String query = "SELECT * FROM weather order by time desc limit 10";
        List<WeatherData> weatherList = defaultAnalysisEngineAdapter.executeQuery(query, WeatherData.class);

        for (WeatherData weatherData : weatherList) {
            System.out.println(weatherData);
        }

    }

    @Test
    void writePoints() {
        // Generate weather data for the past    10 days, one point per minute
        LocalDateTime baseTime = LocalDateTime.now().minusDays(10).truncatedTo(ChronoUnit.DAYS); // Start at midnight 10 days ago
        int minutesInDay = 24 * 60; // 1440 minutes in a day
        int totalDays = 10;
        int batchSize = 1440; // Process one day's worth of points at a time (1440 minutes)
        int totalPoints = 0;
        Random random = new Random(); // Reuse Random instance for efficiency

        long startTime = System.currentTimeMillis();

        for (int day = 0; day < totalDays; day++) {
            List<Point> points = new ArrayList<>(batchSize);
            LocalDateTime dayStartTime = baseTime.plusDays(day);

            // Generate points for each minute of the day
            for (int minute = 0; minute < minutesInDay; minute++) {
                LocalDateTime currentTime = dayStartTime.plusMinutes(minute);
                Instant instant = currentTime.atZone(ZoneId.of("Asia/Shanghai")).toInstant();

                Point point = new Point("weather");
                point.setTimestamp(instant);
                point.setTag("location", "Hangzhou");
                point.setTag("devNo", "dev_" + random.nextInt(10));
                point.setField("temp", Math.random() * 30); // Temperature between 0 and 30°C
                point.setField("hum", Math.random() * 100); // Humidity between 0 and 100%
                // Removed redundant time_str field
                points.add(point);
            }

            // Write points for the current day
            long batchStartTime = System.currentTimeMillis();
            defaultAnalysisEngineAdapter.writePoints(points);
            long batchEndTime = System.currentTimeMillis();

            System.out.println("Day " + day + " write cost: " + (batchEndTime - batchStartTime) + " ms, size: " + points.size());
            totalPoints += points.size();
        }

        long endTime = System.currentTimeMillis();
        System.out.println("Total cost: " + (endTime - startTime) + " ms, total size: " + totalPoints);
    }

}