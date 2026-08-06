package org.influx3xtend.example.test;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.influx3xtend.core.api.Influx3xtendClient;
import org.influx3xtend.example.converter.MqttLineProtocolConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 真实生产场景高并发持续运行模拟器 (ContinuousRealworldSimulationRunner)
 */
public class ContinuousRealworldSimulationRunner {

    private static final Logger log = LoggerFactory.getLogger(ContinuousRealworldSimulationRunner.class);

    private static final String BROKER_URL = "tcp://localhost:1883";
    private static final String TOPIC = "iot/telemetry/DEV_SIM_01";
    private static final String USER_NAME = "influx3xtend";
    private static final String USER_PASS = "influx3xtend";

    private static final AtomicLong publishedMessages = new AtomicLong(0);
    private static final AtomicLong consumedRecords = new AtomicLong(0);

    public static void main(String[] args) {
        log.info("==========================================================");
        log.info("Starting Influx3xtend Realworld Simulation Pipeline Test...");
        log.info("==========================================================");

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        try (Influx3xtendClient client = Influx3xtendClient.builder().build();
             MqttClient subscriber = new MqttClient(BROKER_URL, "sim_sub_" + System.currentTimeMillis());
             MqttClient publisher = new MqttClient(BROKER_URL, "sim_pub_" + System.currentTimeMillis())) {

            MqttConnectOptions subOpts = new MqttConnectOptions();
            subOpts.setUserName(USER_NAME);
            subOpts.setPassword(USER_PASS.toCharArray());
            subOpts.setCleanSession(true);
            subscriber.connect(subOpts);

            subscriber.subscribe(TOPIC, (topic, message) -> {
                String json = new String(message.getPayload(), StandardCharsets.UTF_8);
                try {
                    String lineProtocol = MqttLineProtocolConverter.toLineProtocol(json, "DEV_SIM_01", "regular_telemetry");
                    if (lineProtocol != null && !lineProtocol.isBlank()) {
                        client.writeRecord(lineProtocol);
                        consumedRecords.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Failed to ingest MQTT message", e);
                }
            });

            MqttConnectOptions pubOpts = new MqttConnectOptions();
            pubOpts.setUserName(USER_NAME);
            pubOpts.setPassword(USER_PASS.toCharArray());
            pubOpts.setCleanSession(true);
            publisher.connect(pubOpts);

            Random random = new Random();

            scheduler.scheduleAtFixedRate(() -> {
                try {
                    double temp = 20.0 + random.nextDouble() * 15.0;
                    double vib = 0.05 + random.nextDouble() * 0.5;
                    String payload = String.format("{\"time\":%d,\"temperature\":%.2f,\"vibration\":%.2f}",
                            System.currentTimeMillis(), temp, vib);

                    publisher.publish(TOPIC, payload.getBytes(StandardCharsets.UTF_8), 0, false);
                    publishedMessages.incrementAndGet();
                } catch (Exception e) {
                    log.error("Failed to publish simulation message", e);
                }
            }, 0, 50, TimeUnit.MILLISECONDS);

            log.info("Pipeline running... Press Ctrl+C or wait for 5 seconds of sample run.");
            Thread.sleep(3000);

            log.info("Simulation Progress: Published = {} msgs, Ingested = {} records",
                    publishedMessages.get(), consumedRecords.get());

            publisher.disconnect();
            subscriber.disconnect();

        } catch (Exception e) {
            log.error("Simulation runner encountered error", e);
        } finally {
            scheduler.shutdown();
        }
    }
}
