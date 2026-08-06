package org.influx3xtend.example.converter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class MqttLineProtocolConverterTest {

    @Test
    @DisplayName("测试通用 JSON 遥测对象转 Line Protocol")
    public void testToLineProtocolRegular() {
        String json = "{\"time\": 1785226478000, \"temperature\": 25.5, \"humidity\": 60.0, \"motorState\": 1}";
        String lp = MqttLineProtocolConverter.toLineProtocol(json, "DEV_REG_001", "regular_telemetry");

        assertThat(lp).isNotNull();
        assertThat(lp).startsWith("regular_telemetry,device_id=DEV_REG_001 ");
        assertThat(lp).contains("temperature=25.5");
        assertThat(lp).contains("humidity=60.0");
        assertThat(lp).contains("motorState=1");
        assertThat(lp).endsWith("1785226478000000000\n");
    }

    @Test
    @DisplayName("测试 10kHz 工业点阵格式转 Line Protocol")
    public void testToLineProtocol10kHzBatch() {
        String json = "{\"ts\": 1785226478000000000, \"interval\": 100000, \"fields\": [\"vibration\", \"speed\"], \"values\": [[0.12, 1500], [0.15, 1502]]}";
        String lp = MqttLineProtocolConverter.toLineProtocol(json, "DEV_HF_002", "high_frequency_waveform");

        assertThat(lp).isNotNull();
        String[] lines = lp.trim().split("\n");
        assertThat(lines).hasSize(2);

        assertThat(lines[0]).startsWith("high_frequency_waveform,device_id=DEV_HF_002 vibration=0.12,speed=1500 1785226478000000000");
        assertThat(lines[1]).startsWith("high_frequency_waveform,device_id=DEV_HF_002 vibration=0.15,speed=1502 1785226478000100000");
    }
}
