package org.influx3xtend.example.config;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.influx3xtend.core.api.Influx3xtendClient;
import org.influx3xtend.example.converter.MqttLineProtocolConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

/**
 * Spring Integration MQTT 协议适配与集成配置 (MqttIntegrationConfig)
 * 自动订阅 MQTT Broker 上的 iot/telemetry/+ 主题，将解包后的数据调用 Influx3xtend SDK 持久化落地
 */
@Configuration
public class MqttIntegrationConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttIntegrationConfig.class);

    @Value("${mqtt.broker.url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${mqtt.client.id:influx3xtend_spring_subscriber}")
    private String clientId;

    @Value("${mqtt.topic:iot/telemetry/+}")
    private String defaultTopic;

    @Value("${mqtt.username:influx3xtend}")
    private String username;

    @Value("${mqtt.password:influx3xtend}")
    private String password;

    @Autowired
    private Influx3xtendClient influx3xtendClient;

    @Bean
    public MqttPahoClientFactory mqttClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[]{brokerUrl});
        if (username != null && !username.isBlank()) {
            options.setUserName(username);
            options.setPassword(password.toCharArray());
        }
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(60);
        options.setAutomaticReconnect(true);
        factory.setConnectionOptions(options);
        return factory;
    }

    @Bean
    public MessageChannel mqttInputChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageProducer inbound() {
        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(clientId, mqttClientFactory(), defaultTopic);
        adapter.setCompletionTimeout(5000);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(0);
        adapter.setOutputChannel(mqttInputChannel());
        return adapter;
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttInputChannel")
    public MessageHandler handler() {
        return (Message<?> message) -> {
            String topic = (String) message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
            String payload = message.getPayload().toString();

            if (topic == null || payload == null || payload.isBlank()) {
                return;
            }

            // 从主题中提取 deviceId (例如 iot/telemetry/DEV_REG_001 -> DEV_REG_001)
            String deviceId = "UNKNOWN_DEV";
            if (topic.contains("/")) {
                deviceId = topic.substring(topic.lastIndexOf("/") + 1);
            }

            String measurement = payload.contains("\"interval\": 100000") || payload.contains("\"interval\":100000")
                    ? "high_frequency_waveform"
                    : "regular_telemetry";

            try {
                String lineProtocol = MqttLineProtocolConverter.toLineProtocol(payload, deviceId, measurement);
                if (lineProtocol != null && !lineProtocol.isBlank()) {
                    influx3xtendClient.writeRecord(lineProtocol);
                    log.debug("Spring Integration MQTT: Successfully ingested data from topic {} into measurement {}",
                            topic, measurement);
                }
            } catch (Exception e) {
                log.warn("Failed to parse & ingest MQTT payload from topic {}: {}", topic, e.getMessage());
            }
        };
    }
}
