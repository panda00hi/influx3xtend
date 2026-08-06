package org.influx3xtend.core.storage;

import org.influx3xtend.core.config.LocalStorageConfig;
import org.influx3xtend.core.config.S3StorageConfig;
import org.influx3xtend.core.config.StorageConfig;
import org.influx3xtend.core.constants.Influx3xtendConstants;
import org.influx3xtend.core.model.TimeRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 对象存储与本地 Parquet 存储目录扫描器 (ObjectStorageScanner)
 */
public class ObjectStorageScanner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageScanner.class);

    private final StorageConfig storageConfig;
    private S3Client s3Client;

    public ObjectStorageScanner(StorageConfig storageConfig) {
        this.storageConfig = storageConfig;
        if (storageConfig instanceof S3StorageConfig s3Config && s3Config.bucket() != null) {
            initS3Client(s3Config);
        }
    }

    private void initS3Client(S3StorageConfig s3Config) {
        try {
            String regionStr = s3Config.region() != null ? s3Config.region() : Influx3xtendConstants.DEFAULT_S3_REGION;
            var builder = S3Client.builder().region(Region.of(regionStr));

            if (s3Config.endpoint() != null && !s3Config.endpoint().isBlank()) {
                builder.endpointOverride(URI.create(s3Config.endpoint()));
            }

            if (s3Config.accessKey() != null && s3Config.secretKey() != null) {
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3Config.accessKey(), s3Config.secretKey())
                ));
            }

            this.s3Client = builder.build();
            log.info("Initialized S3Client for bucket: {}", s3Config.bucket());
        } catch (Exception e) {
            log.warn("Failed to initialize S3Client: {}", e.getMessage());
        }
    }

    /**
     * 根据 measurement 名称与时间切片查找覆盖的 Parquet 文件路径列表
     */
    public List<String> scanParquetFiles(String measurement, TimeRange range) {
        if (storageConfig instanceof LocalStorageConfig localConfig) {
            return scanLocalParquetFiles(localConfig, measurement, range);
        }
        if (storageConfig instanceof S3StorageConfig s3Config) {
            return scanS3ParquetFiles(s3Config, measurement);
        }
        log.debug("No active storage configuration for measurement [{}]", measurement);
        return List.of();
    }

    private List<String> scanS3ParquetFiles(S3StorageConfig s3Config, String measurement) {
        if (s3Client == null) {
            String bucket = s3Config.bucket() != null ? s3Config.bucket() : "bucket";
            return List.of(Influx3xtendConstants.S3_SCHEME_PREFIX + bucket + "/" + measurement + "/*/*/*/*" + Influx3xtendConstants.PARQUET_EXTENSION);
        }

        List<String> matchedFiles = new ArrayList<>();
        try {
            String prefix = measurement + "/";
            String continuationToken = null;
            do {
                ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                        .bucket(s3Config.bucket())
                        .prefix(prefix);
                if (continuationToken != null) {
                    requestBuilder.continuationToken(continuationToken);
                }

                var response = s3Client.listObjectsV2(requestBuilder.build());
                for (S3Object s3Object : response.contents()) {
                    if (s3Object.key().endsWith(Influx3xtendConstants.PARQUET_EXTENSION)) {
                        matchedFiles.add(Influx3xtendConstants.S3_SCHEME_PREFIX + s3Config.bucket() + "/" + s3Object.key());
                    }
                }
                continuationToken = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
            } while (continuationToken != null);
        } catch (Exception e) {
            log.warn("S3 listObjectsV2 failed for measurement {}: {}", measurement, e.getMessage());
            matchedFiles.add(Influx3xtendConstants.S3_SCHEME_PREFIX + s3Config.bucket() + "/" + measurement + "/*/*/*/*" + Influx3xtendConstants.PARQUET_EXTENSION);
        }

        return matchedFiles.isEmpty()
                ? List.of(Influx3xtendConstants.S3_SCHEME_PREFIX + s3Config.bucket() + "/" + measurement + "/*/*/*/*" + Influx3xtendConstants.PARQUET_EXTENSION)
                : matchedFiles;
    }

    private List<String> scanLocalParquetFiles(LocalStorageConfig localConfig, String measurement, TimeRange range) {
        Path baseDirPath = Paths.get(localConfig.baseDir());
        if (!Files.exists(baseDirPath)) {
            log.debug("Local parquet base directory does not exist: {}", baseDirPath);
            return List.of();
        }

        Path scanPath = baseDirPath.resolve(measurement);
        if (!Files.exists(scanPath)) {
            scanPath = baseDirPath;
        }

        // 依据请求时间跨度提取 UTC 日期集合，进行精准 Date Partition Pruning 动态剪枝
        Set<String> dateSubstrings = new HashSet<>();
        if (range != null && range.start() != null && range.end() != null) {
            LocalDate startDate = range.start().atZone(ZoneId.of("UTC")).toLocalDate();
            LocalDate endDate = range.end().atZone(ZoneId.of("UTC")).toLocalDate();
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                dateSubstrings.add(date.toString());
            }
        }

        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(scanPath)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(Influx3xtendConstants.PARQUET_EXTENSION))
                  .filter(p -> {
                      if (dateSubstrings.isEmpty()) return true;
                      String pathStr = p.toString();
                      for (String dateStr : dateSubstrings) {
                          if (pathStr.contains(dateStr)) return true;
                      }
                      return false;
                  })
                  .forEach(p -> files.add(p.toAbsolutePath().toString()));
        } catch (Exception e) {
            log.warn("Failed to scan local parquet directory at {}: {}", scanPath, e.getMessage());
        }

        if (files.isEmpty()) {
            log.debug("No .parquet files found in local directory: {}", scanPath);
            return List.of();
        }
        return files;
    }

    @Override
    public void close() {
        if (s3Client != null) {
            try {
                s3Client.close();
            } catch (Exception e) {
                log.warn("Error closing S3Client", e);
            }
        }
    }
}
