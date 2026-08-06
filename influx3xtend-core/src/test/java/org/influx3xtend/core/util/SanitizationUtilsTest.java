package org.influx3xtend.core.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SanitizationUtilsTest {

    @Test
    public void testValidIdentifiers() {
        assertThat(SanitizationUtils.sanitizeIdentifier("cpu", "measurement")).isEqualTo("cpu");
        assertThat(SanitizationUtils.sanitizeIdentifier("cpu_load_15", "measurement")).isEqualTo("cpu_load_15");
        assertThat(SanitizationUtils.sanitizeIdentifier("time", "timeColumn")).isEqualTo("time");
    }

    @Test
    public void testInvalidIdentifiersThrowException() {
        assertThatThrownBy(() -> SanitizationUtils.sanitizeIdentifier("cpu; DROP TABLE telemetry;", "measurement"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid SQL identifier");

        assertThatThrownBy(() -> SanitizationUtils.sanitizeIdentifier("1=1 --", "timeColumn"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid SQL identifier");

        assertThatThrownBy(() -> SanitizationUtils.sanitizeIdentifier("host-name", "tagField"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testSanitizeOperators() {
        assertThat(SanitizationUtils.sanitizeOperator("=")).isEqualTo("=");
        assertThat(SanitizationUtils.sanitizeOperator(">=")).isEqualTo(">=");
        assertThat(SanitizationUtils.sanitizeOperator("like")).isEqualTo("LIKE");

        assertThatThrownBy(() -> SanitizationUtils.sanitizeOperator(";--"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
