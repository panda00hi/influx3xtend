package org.influx3xtend.core.config;

import java.util.Objects;

/**
 * 对象存储 / S3 历史 Parquet 访问配置
 */
public record S3StorageConfig(
        String endpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey
) implements StorageConfig {
    public S3StorageConfig {
        Objects.requireNonNull(bucket, "S3 bucket cannot be null");
    }

    @Override
    public StorageType type() {
        return StorageType.S3_OBJECT;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String endpoint;
        private String region = "us-east-1";
        private String bucket;
        private String accessKey;
        private String secretKey;

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder region(String region) {
            this.region = region;
            return this;
        }

        public Builder bucket(String bucket) {
            this.bucket = bucket;
            return this;
        }

        public Builder accessKey(String accessKey) {
            this.accessKey = accessKey;
            return this;
        }

        public Builder secretKey(String secretKey) {
            this.secretKey = secretKey;
            return this;
        }

        public S3StorageConfig build() {
            return new S3StorageConfig(endpoint, region, bucket, accessKey, secretKey);
        }
    }
}
