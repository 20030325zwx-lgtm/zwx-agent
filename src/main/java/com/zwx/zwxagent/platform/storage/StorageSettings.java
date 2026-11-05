package com.zwx.zwxagent.platform.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对象存储配置（app.storage.*）。provider: s3（自建 MinIO/S3 兼容）/ oss（阿里云，历史与回滚）/ local（桌面）。
 * fallback-provider 只用于读取兜底，迁移期设为 oss 可保证旧对象仍可读。
 */
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageSettings {

    private String provider = "oss";
    private String fallbackProvider = "";
    private String localDir = "";
    private S3 s3 = new S3();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getFallbackProvider() {
        return fallbackProvider;
    }

    public void setFallbackProvider(String fallbackProvider) {
        this.fallbackProvider = fallbackProvider;
    }

    public String getLocalDir() {
        return localDir;
    }

    public void setLocalDir(String localDir) {
        this.localDir = localDir;
    }

    public S3 getS3() {
        return s3;
    }

    public void setS3(S3 s3) {
        this.s3 = s3 == null ? new S3() : s3;
    }

    public boolean s3Configured() {
        return notBlank(s3.getEndpoint()) && notBlank(s3.getAccessKey()) && notBlank(s3.getSecretKey());
    }

    public static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public static class S3 {

        private String endpoint = "";
        private String accessKey = "";
        private String secretKey = "";
        private String region = "us-east-1";
        private String bucket = "aster-media";
        private String publicEndpoint = "";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getPublicEndpoint() {
            return publicEndpoint;
        }

        public void setPublicEndpoint(String publicEndpoint) {
            this.publicEndpoint = publicEndpoint;
        }
    }
}
