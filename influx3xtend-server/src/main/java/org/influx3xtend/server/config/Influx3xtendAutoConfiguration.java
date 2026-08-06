package org.influx3xtend.server.config;

import org.influx3xtend.core.api.Influx3xtendClient;
import org.influx3xtend.core.config.InfluxDB3Config;
import org.influx3xtend.core.config.S3StorageConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(Influx3xtendProperties.class)
public class Influx3xtendAutoConfiguration {

    @Bean(destroyMethod = "close")
    public Influx3xtendClient influx3xtendClient(Influx3xtendProperties properties) {
        var influx3Props = properties.getInflux3();
        var storageProps = properties.getStorage();

        InfluxDB3Config influx3Config = InfluxDB3Config.builder()
                .host(influx3Props.getHost())
                .token(influx3Props.getToken())
                .database(influx3Props.getDatabase())
                .organization(influx3Props.getOrganization())
                .build();

        S3StorageConfig storageConfig = S3StorageConfig.builder()
                .endpoint(storageProps.getEndpoint())
                .region(storageProps.getRegion())
                .bucket(storageProps.getBucket())
                .accessKey(storageProps.getAccessKey())
                .secretKey(storageProps.getSecretKey())
                .build();

        var builder = Influx3xtendClient.builder()
                .influx3Config(influx3Config)
                .storageConfig(storageConfig)
                .duckdbMaxMemory(properties.getDuckdbMaxMemory());

        if (storageProps.getLocalStorageDir() != null && !storageProps.getLocalStorageDir().isBlank()) {
            builder.localStorageDir(storageProps.getLocalStorageDir());
        }

        return builder.build();
    }
}
