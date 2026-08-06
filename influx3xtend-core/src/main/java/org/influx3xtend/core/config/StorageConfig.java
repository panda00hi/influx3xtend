package org.influx3xtend.core.config;

/**
 * 历史 Parquet 数据存储统一抽象接口 (StorageConfig)
 */
public sealed interface StorageConfig permits LocalStorageConfig, S3StorageConfig {

    /**
     * 获取存储引擎类型
     */
    StorageType type();
}
