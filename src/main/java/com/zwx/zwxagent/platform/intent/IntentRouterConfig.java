package com.zwx.zwxagent.platform.intent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Optional;

/**
 * 意图路由器选型：app.intent.provider = none（默认，禁用）/ jev。
 * 2026-11-08 起独立 LLM 意图分类器退出聊天主链路（OrchestrationRouting 接管编排分档），
 * 本路由器仅服务评测平台（InternalEvalController）与未来复活场景；none 时返回恒空路由器。
 */
@Configuration
public class IntentRouterConfig {

    @Bean
    @Primary
    public IntentRouter intentRouter(JevIntentRouter jevRouter,
                                     @Value("${app.intent.provider:none}") String provider) {
        String selected = provider == null ? "none" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        org.slf4j.LoggerFactory.getLogger(IntentRouterConfig.class)
                .info("intent router provider = {}（none=禁用/jev）", selected);
        return switch (selected) {
            case "jev" -> jevRouter;
            default -> message -> Optional.empty();
        };
    }
}
