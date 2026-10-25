package com.zwx.zwxagent.platform.intent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Optional;

/**
 * 意图路由器选型：app.intent.provider = none（默认，禁用）/ jev / qwen。
 * 两个实现始终装配（各自内部有空 Key/异常降级），由本配置决定 primary 暴露哪一个；
 * none 时返回恒空路由器，行为与未引入该功能完全一致。
 */
@Configuration
public class IntentRouterConfig {

    @Bean
    @Primary
    public IntentRouter intentRouter(JevIntentRouter jevRouter, QwenIntentRouter qwenRouter,
                                     @Value("${app.intent.provider:none}") String provider) {
        String selected = provider == null ? "none" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        org.slf4j.LoggerFactory.getLogger(IntentRouterConfig.class)
                .info("intent router provider = {}（none=禁用/jev/qwen）", selected);
        return switch (selected) {
            case "jev" -> jevRouter;
            case "qwen" -> qwenRouter;
            default -> message -> Optional.empty();
        };
    }
}
