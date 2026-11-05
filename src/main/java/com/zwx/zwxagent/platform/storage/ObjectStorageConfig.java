package com.zwx.zwxagent.platform.storage;

import com.zwx.zwxagent.knowledge.storage.OssClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.nio.file.Path;

/**
 * 存储装配（2026-11-05 迁移 MinIO）：
 * - 主存储由 app.storage.provider 选择，s3 走自建 MinIO，oss/local 保持旧行为；
 * - fallback-provider 只接管读取，用于旧对象仍在 OSS 的过渡期；
 * - provider=oss 且未配置 OSS 凭据时，按旧行为回退本地目录并告警（桌面/开发）；
 * - provider=s3 但配置不全直接失败，避免把线上媒体静默写进容器临时磁盘。
 */
@Configuration
public class ObjectStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageConfig.class);

    @Bean
    public LocalObjectStorage localObjectStorage(StorageSettings settings,
                                                @Value("${app.storage.local-dir:${user.home}/.aster/creative-media}") String localDir) {
        String dir = StorageSettings.notBlank(settings.getLocalDir()) ? settings.getLocalDir() : localDir;
        return new LocalObjectStorage(Path.of(dir));
    }

    @Bean
    public OssObjectStorage ossObjectStorage(OssClientProvider ossClientProvider,
                                             @Value("${app.oss.bucket:}") String bucket) {
        return new OssObjectStorage(ossClientProvider, bucket);
    }

    /** MinIO/S3 只在配置了地址与密钥时装配，避免本地未部署 MinIO 时启动失败。 */
    @Bean
    @ConditionalOnExpression("'${app.storage.s3.endpoint:}' != ''")
    public S3ObjectStorage s3ObjectStorage(StorageSettings settings) {
        var s3 = settings.getS3();
        S3ObjectStorage storage = new S3ObjectStorage(s3.getEndpoint(), s3.getAccessKey(), s3.getSecretKey(),
                s3.getRegion(), s3.getBucket(), s3.getPublicEndpoint());
        storage.ensureBucket();
        log.info("对象存储：S3 兼容后端就绪 endpoint={} bucket={}", s3.getEndpoint(), s3.getBucket());
        return storage;
    }

    @Bean
    @Primary
    public ObjectStorage objectStorageRouter(StorageSettings settings,
                                       OssObjectStorage ossObjectStorage,
                                       LocalObjectStorage localObjectStorage,
                                       ObjectProvider<S3ObjectStorage> s3Provider,
                                       @Value("${app.oss.access-key-id:}") String ossAccessKeyId,
                                       @Value("${app.oss.access-key-secret:}") String ossAccessKeySecret) {
        boolean ossConfigured = StorageSettings.notBlank(ossAccessKeyId) && StorageSettings.notBlank(ossAccessKeySecret);
        ObjectStorage primary = switch (normalize(settings.getProvider())) {
            case "s3" -> {
                S3ObjectStorage s3 = s3Provider.getIfAvailable();
                if (s3 == null) {
                    throw new IllegalStateException("对象存储配置为 s3，但缺少 MINIO_ENDPOINT/MINIO_ACCESS_KEY/MINIO_SECRET_KEY");
                }
                yield s3;
            }
            case "local" -> localObjectStorage;
            default -> {
                if (!ossConfigured) {
                    log.warn("对象存储配置为 oss 但缺少凭据，回退本地目录（不输出密钥）");
                    yield localObjectStorage;
                }
                yield ossObjectStorage;
            }
        };
        ObjectStorage fallback = switch (normalize(settings.getFallbackProvider())) {
            case "oss" -> ossConfigured ? ossObjectStorage : null;
            case "local" -> localObjectStorage;
            default -> null;
        };
        if (fallback != null && fallback == primary) fallback = null;
        log.info("对象存储：主={} 兜底={}", primary.backend(), fallback == null ? "无" : fallback.backend());
        return new ObjectStorageRouter(primary, fallback);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "oss" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
