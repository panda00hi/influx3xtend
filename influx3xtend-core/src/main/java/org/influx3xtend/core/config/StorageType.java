package org.influx3xtend.core.config;

/**
 * 历史 Parquet 数据存储引擎类型枚举 (StorageType)
 */
public enum StorageType {
    /**
     * 本地磁盘目录
     */
    LOCAL_DISK,

    /**
     * S3 对象存储 / MinIO
     */
    S3_OBJECT
}
