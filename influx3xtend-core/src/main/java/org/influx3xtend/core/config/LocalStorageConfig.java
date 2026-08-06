package org.influx3xtend.core.config;

import java.util.Objects;

/**
 * 本地磁盘 Parquet 历史文件访问配置
 */
public record LocalStorageConfig(
        String baseDir
) implements StorageConfig {
    public LocalStorageConfig {
        Objects.requireNonNull(baseDir, "baseDir cannot be null");
    }

    @Override
    public StorageType type() {
        return StorageType.LOCAL_DISK;
    }

    public static LocalStorageConfig of(String baseDir) {
        return new LocalStorageConfig(baseDir);
    }
}
