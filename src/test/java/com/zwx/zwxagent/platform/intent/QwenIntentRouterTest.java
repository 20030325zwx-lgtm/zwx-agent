package com.zwx.zwxagent.platform.intent;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class QwenIntentRouterTest {

    private QwenIntentRouter router(String apiKey) {
        return new QwenIntentRouter("https://dashscope.aliyuncs.com", apiKey, "qwen-turbo", 2500);
    }

    private static final String RESPONSE = """
            {
              "choices": [{"message": {"role": "assistant", "content":
                "{\\"intent\\":\\"知识库问答\\",\\"confidence\\":0.9,\\"complexity\\":0.3,\\"needs_knowledge\\":0.95}"}}],
              "usage": {"total_tokens": 180}
            }
            """;

    @Test
    void parsesJsonContentIntoDecision() throws Exception {
        IntentDecision decision = router("key").parse(RESPONSE, 640);

        assertThat(decision.intent()).isEqualTo("知识库问答");
        assertThat(decision.intentConfidence()).isEqualTo(0.9);
        assertThat(decision.complexity()).isEqualTo(0.3);
        assertThat(decision.needsKnowledge()).isEqualTo(0.95);
        assertThat(decision.latencyMs()).isEqualTo(640);
    }

    @Test
    void parsesFencedOrNoisyLlmOutput() throws Exception {
        String noisy = RESPONSE.replace("\\n", "\n").replace("{\\\"", "{\n  \"")
                .replace("\\", "");
        // 即使内容外面包了 ```json 标记或文字，也取 {..} 片段解析
        String fenced = "```json\n" + new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(java.util.Map.of(
                        "intent", "闲聊问答", "confidence", 0.95, "complexity", 0.0, "needs_knowledge", 0.02))
                + "\n```";
        IntentDecision decision = router("key").parse(
                "{\"choices\":[{\"message\":{\"content\":" + new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(fenced) + "}}]}", 500);

        assertThat(decision.intent()).isEqualTo("闲聊问答");
        assertThat(decision.intentConfidence()).isEqualTo(0.95);
    }

    @Test
    void buildRequestCarriesSystemPromptAndUserMessage() throws Exception {
        var request = router("key").buildRequest("帮我把事故数据整理成表格");
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(request);

        assertThat(json.path("model").asText()).isEqualTo("qwen-turbo");
        assertThat(json.path("messages")).hasSize(2);
        assertThat(json.path("messages").path(0).path("content").asText()).contains("意图识别器");
        assertThat(json.path("messages").path(1).path("content").asText()).isEqualTo("帮我把事故数据整理成表格");
    }

    @Test
    void keylessRouterClassifiesNothing() {
        assertThat(router("").classify("你好")).isEmpty();
        assertThat(router("   ").classify("你好")).isEmpty();
        assertThat(router("key").classify("  ")).isEmpty();
        assertThat(router("key").classify(null)).isEmpty();
    }

    @Test
    void unreachableEndpointFallsBackToEmpty() {
        QwenIntentRouter unreachable = new QwenIntentRouter("http://127.0.0.1:1", "key", "qwen-turbo", 300);
        assertThat(unreachable.classify("你好")).isEmpty();
    }

    @Test
    void providerConfigSelectsRouterOrDisables() {
        IntentRouterConfig config = new IntentRouterConfig();
        JevIntentRouter jev = new JevIntentRouter(true, "https://api.typesafe.ai", "k", "jev-latest", 1500);
        QwenIntentRouter qwen = new QwenIntentRouter("https://dashscope.aliyuncs.com", "k", "qwen-turbo", 2500);

        assertThat(config.intentRouter(jev, qwen, "jev").classify(null)).isEmpty();
        assertThat(config.intentRouter(jev, qwen, "qwen")).isSameAs(qwen);
        assertThat(config.intentRouter(jev, qwen, "jev")).isSameAs(jev);
        assertThat(config.intentRouter(jev, qwen, null)).isNotNull();
        assertThat(config.intentRouter(jev, qwen, "unknown").classify("你好")).isEmpty();
    }
}
