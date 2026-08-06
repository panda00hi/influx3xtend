package org.influx3xtend.example.test;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.influx3xtend.example.converter.MqttLineProtocolConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 真实 EMQX Docker 实例交互验证 runner (EmqxMqttLiveTestRunner)
 */
public class EmqxMqttLiveTestRunner {

    private static final Logger log = LoggerFactory.getLogger(EmqxMqttLiveTestRunner.class);

    private static final String BROKER_URL = "tcp://localhost:1883";
    private static final String CLIENT_ID = "influx3xtend_emqx_live_runner";
    private static final String TOPIC = "iot/telemetry/EMQX_DEV_01";
    private static final String USERNAME = "influx3xtend";
    private static final String PASSWORD = "influx3xtend";

    public static void main(String[] args) {
        log.info("Starting EMQX Docker Live Integration Test...");
        CountDownLatch messageLatch = new CountDownLatch(1);

        try (MqttClient client = new MqttClient(BROKER_URL, CLIENT_ID)) {
            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(USERNAME);
            options.setPassword(PASSWORD.toCharArray());
            options.setCleanSession(true);

            log.info("Connecting to EMQX Broker Container at {}...", BROKER_URL);
            client.connect(options);
            log.info("Successfully connected to EMQX!");

            // 订阅主题并接收边缘报文
            client.subscribe(TOPIC, (topic, message) -> {
                String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                log.info("[MQTT Recv] Topic: {}, Payload Size: {} bytes", topic, payload.length());

                // 使用 MqttLineProtocolConverter 解析为 Line Protocol
                try {
                    String lineProtocol = MqttLineProtocolConverter.toLineProtocol(payload, "EMQX_DEV_01", "regular_telemetry");
                    if (lineProtocol != null) {
                        log.info("[Pass] Converted Line Protocol:\n{}", lineProtocol);
                    }
                } catch (Exception e) {
                    log.error("Failed to parse incoming MQTT payload", e);
                } finally {
                    messageLatch.countDown();
                }
            });

            // 模拟发送一条边缘测试 JSON 报文
            String testPayload = """
                    {
                      "time": 1785223800000,
                      "temperature": 26.8,
                      "humidity": 58.2,
                      "pressure": 101.3
                    }
                    """;

            log.info("Publishing test message to topic {}...", TOPIC);
            client.publish(TOPIC, testPayload.getBytes(StandardCharsets.UTF_8), 0, false);

            boolean received = messageLatch.await(5, TimeUnit.SECONDS);
            if (received) {
                log.info("EMQX Live Test Completed Successfully! Message received and parsed.");
            } else {
                log.error("EMQX Live Test Failed: Message was not received within timeout.");
            }

            client.disconnect();
        } catch (Exception e) {
            log.error("EMQX Live Integration Test failed with exception", e);
        }
    }
}
