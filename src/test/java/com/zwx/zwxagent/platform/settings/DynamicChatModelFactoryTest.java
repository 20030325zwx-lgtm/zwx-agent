package com.zwx.zwxagent.platform.settings;

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
    void completionsPathKeepsVersionSegmentInBaseUrl() {
        Assertions.assertEquals("/chat/completions",
                DynamicChatModelFactory.completionsPath("https://open.bigmodel.cn/api/paas/v4"));
        Assertions.assertEquals("/chat/completions",
                DynamicChatModelFactory.completionsPath("https://open.bigmodel.cn/api/paas/v4/"));
        Assertions.assertEquals("/chat/completions",
                DynamicChatModelFactory.completionsPath("https://api.openai.com/v1"));
        Assertions.assertEquals("/chat/completions",
                DynamicChatModelFactory.completionsPath("https://dashscope.aliyuncs.com/compatible-mode/v1"));
        Assertions.assertEquals("/v1/chat/completions",
                DynamicChatModelFactory.completionsPath("https://api.openai.com"));
        Assertions.assertEquals("/v1/chat/completions",
                DynamicChatModelFactory.completionsPath("https://api.example.com/openai"));
    }

    @Test
    void buildsDashScopeModelFromResolvedConfig() {
        ResolvedModelConfig config = new ResolvedModelConfig("chat", "dashscope",
                "https://dashscope.aliyuncs.com", "qwen-plus", "sk-test", "USER");
        Assertions.assertNotNull(factory.model(config));
        Assertions.assertSame(factory.model(config), factory.model(config));
    }
}
