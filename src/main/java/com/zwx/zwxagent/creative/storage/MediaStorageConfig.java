package com.zwx.zwxagent.creative.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.nio.file.Path;

/**
 * 存储路由：creative.storage.backend=oss（默认，云端）或 local（桌面/测试）。
 * 配置为 oss 但缺少 OSS 凭据时回退本地目录并显式告警，避免桌面端首次生成直接失败（C15）。
 */
@Configuration
public class MediaStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(MediaStorageConfig.class);

    @Bean
    public LocalMediaStorage localMediaStorage(
            @Value("${creative.storage.local-dir:${user.home}/.aster/creative-media}") String localDir) {
        return new LocalMediaStorage(Path.of(localDir));
    }

    @Bean
    @Primary
    public MediaStorage mediaStorage(OssMediaStorage ossMediaStorage,
                                     LocalMediaStorage localMediaStorage,
                                     @Value("${creative.storage.backend:oss}") String backend,
                                     @Value("${app.oss.access-key-id:}") String ossAccessKeyId,
                                     @Value("${app.oss.access-key-secret:}") String ossAccessKeySecret) {
        boolean wantsOss = "oss".equalsIgnoreCase(backend);
        boolean ossConfigured = ossAccessKeyId != null && !ossAccessKeyId.isBlank()
                && ossAccessKeySecret != null && !ossAccessKeySecret.isBlank();
        if (wantsOss && !ossConfigured) {
            log.warn("创作媒体存储：未配置 OSS 凭据，回退本地目录（不输出密钥）");
            return localMediaStorage;
        }
        return wantsOss ? ossMediaStorage : localMediaStorage;
    }
}
