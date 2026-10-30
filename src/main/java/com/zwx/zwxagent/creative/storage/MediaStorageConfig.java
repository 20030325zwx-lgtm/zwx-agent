package com.zwx.zwxagent.creative.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 存储路由：creative.storage.backend=oss（默认，云端）或 local（桌面/测试）。
 * 两个实现都注册为普通 Bean，路由 Bean 用 @Primary 选择。
 */
@Configuration
public class MediaStorageConfig {

    @Bean
    @org.springframework.context.annotation.Primary
    public MediaStorage mediaStorage(OssMediaStorage ossMediaStorage,
                                     LocalMediaStorage localMediaStorage,
                                     @org.springframework.beans.factory.annotation.Value(
                                             "${creative.storage.backend:oss}") String backend) {
        return "local".equalsIgnoreCase(backend) ? localMediaStorage : ossMediaStorage;
    }
}
