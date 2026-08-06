package org.influx3xtend.core.constants;

/**
 * Influx3xtend 框架核心常量定义类 (Zero Magic Strings & Numbers)
 */
public final class Influx3xtendConstants {

    private Influx3xtendConstants() {}

    /**
     * 核心时间轴列名
     */
    public static final String COLUMN_TIME = "time";

    /**
     * 自动 SELECT * 时的默认安全行数限制 (600,000 点)
     */
    public static final int DEFAULT_SAFETY_MAX_LIMIT = 600000;

    /**
     * 默认 InfluxDB 3 端口
     */
    public static final int DEFAULT_INFLUX3_PORT = 8086;

    /**
     * 默认 DuckDB 堆外内存使用限制
     */
    public static final String DEFAULT_DUCKDB_MAX_MEMORY = "4GB";

    /**
     * 默认实时 Flight SQL 视窗限制 (72小时)
     */
    public static final String DEFAULT_MAX_NATIVE_QUERY_WINDOW = "72h";

    /**
     * S3 协议前缀
     */
    public static final String S3_SCHEME_PREFIX = "s3://";

    /**
     * Parquet 文件扩展名
     */
    public static final String PARQUET_EXTENSION = ".parquet";

    /**
     * 默认 S3 区域
     */
    public static final String DEFAULT_S3_REGION = "us-east-1";

    /**
     * 纳秒转毫秒除数
     */
    public static final long NS_TO_MS_DIVISOR = 1_000_000L;

    /**
     * 微秒转毫秒除数
     */
    public static final long US_TO_MS_DIVISOR = 1_000L;

    /**
     * 纳秒阈值判定边界 (10^17)
     */
    public static final long NANO_TIMESTAMP_THRESHOLD = 100_000_000_000_000_000L;

    /**
     * 微秒阈值判定边界 (10^14)
     */
    public static final long MICRO_TIMESTAMP_THRESHOLD = 100_000_000_000_000L;
}
