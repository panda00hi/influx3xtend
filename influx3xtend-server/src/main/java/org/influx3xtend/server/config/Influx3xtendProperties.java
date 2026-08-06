package org.influx3xtend.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "influx3xtend")
public class Influx3xtendProperties {

    private Influx3Properties influx3 = new Influx3Properties();
    private StorageProperties storage = new StorageProperties();
    private String duckdbMaxMemory = "4GB";

    public Influx3Properties getInflux3() {
        return influx3;
    }

    public void setInflux3(Influx3Properties influx3) {
        this.influx3 = influx3;
    }

    public StorageProperties getStorage() {
        return storage;
    }

    public void setStorage(StorageProperties storage) {
        this.storage = storage;
    }

    public String getDuckdbMaxMemory() {
        return duckdbMaxMemory;
    }

    public void setDuckdbMaxMemory(String duckdbMaxMemory) {
        this.duckdbMaxMemory = duckdbMaxMemory;
    }

    public static class Influx3Properties {
        private String host = "http://localhost:8086";
        private String token = "default-token";
        private String database = "tsdb";
        private String organization = "";

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
        public String getOrganization() { return organization; }
        public void setOrganization(String organization) { this.organization = organization; }
    }

    public static class StorageProperties {
        private String localStorageDir = "";
        private String endpoint = "https://s3.us-east-1.amazonaws.com";
        private String region = "us-east-1";
        private String bucket = "tsdb-bucket";
        private String accessKey = "";
        private String secretKey = "";

        public String getLocalStorageDir() { return localStorageDir; }
        public void setLocalStorageDir(String localStorageDir) { this.localStorageDir = localStorageDir; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    }
}
