package org.influx3xtend.core.constants;

import java.time.Duration;

/**
 * 框架默认配置管理与 JVM System Properties / 环境变量动态覆盖器 (Influx3xtendDefaults)
 * 优先级：Builder 指定值 > JVM System Property (-Dinflux3xtend...) > Environment Variable > 代码默认值
 */
public final class Influx3xtendDefaults {

    private Influx3xtendDefaults() {}

    public static final String SYS_PROP_DUCKDB_MAX_MEMORY = "influx3xtend.duckdb.max-memory";
    public static final String SYS_PROP_MAX_NATIVE_WINDOW_HOURS = "influx3xtend.router.max-native-window-hours";
    public static final String SYS_PROP_SAFETY_LIMIT = "influx3xtend.query.safety-limit";

    public static String getDuckdbMaxMemory(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String sysProp = System.getProperty(SYS_PROP_DUCKDB_MAX_MEMORY);
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp;
        }
        String env = System.getenv("INFLUX3XTEND_DUCKDB_MAX_MEMORY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return Influx3xtendConstants.DEFAULT_DUCKDB_MAX_MEMORY;
    }

    public static Duration getMaxNativeQueryWindow(Duration configured) {
        if (configured != null) {
            return configured;
        }
        String sysProp = System.getProperty(SYS_PROP_MAX_NATIVE_WINDOW_HOURS);
        if (sysProp != null && !sysProp.isBlank()) {
            try {
                return Duration.ofHours(Long.parseLong(sysProp.trim()));
            } catch (Exception ignored) {}
        }
        return Duration.ofHours(72);
    }

    public static int getSafetyMaxLimit(Integer configured) {
        if (configured != null && configured > 0) {
            return configured;
        }
        String sysProp = System.getProperty(SYS_PROP_SAFETY_LIMIT);
        if (sysProp != null && !sysProp.isBlank()) {
            try {
                return Integer.parseInt(sysProp.trim());
            } catch (Exception ignored) {}
        }
        return Influx3xtendConstants.DEFAULT_SAFETY_MAX_LIMIT;
    }
}
