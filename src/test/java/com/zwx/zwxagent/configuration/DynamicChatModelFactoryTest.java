package com.zwx.zwxagent.configuration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DynamicChatModelFactoryTest {

    private final DynamicChatModelFactory factory = new DynamicChatModelFactory();

    @Test
    void requiresApiKey() {
        ResolvedModelConfig config = new ResolvedModelConfig("chat", "dashscope",
                "https://dashscope.aliyuncs.com", "qwen-plus", null, "ENVIRONMENT");
        Assertions.assertThrows(IllegalStateException.class, () -> factory.model(config));
    }

    @Test
    void openAiCompatibleRequiresBaseUrl() {
        ResolvedModelConfig config = new ResolvedModelConfig("chat", "openai-compatible",
                null, "gpt-4o-mini", "sk-test", "USER");
        Assertions.assertThrows(IllegalStateException.class, () -> factory.model(config));
    }

    @Test
    void buildsDashScopeModelFromResolvedConfig() {
        ResolvedModelConfig config = new ResolvedModelConfig("chat", "dashscope",
                "https://dashscope.aliyuncs.com", "qwen-plus", "sk-test", "USER");
        Assertions.assertNotNull(factory.model(config));
        Assertions.assertSame(factory.model(config), factory.model(config));
    }
}
