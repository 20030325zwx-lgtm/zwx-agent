package com.zwx.zwxagent.platform.intent;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JevIntentRouterTest {

    private JevIntentRouter router(boolean enabled, String apiKey) {
        return new JevIntentRouter(enabled, "https://api.typesafe.ai", apiKey, "jev-latest", 1500);
    }

    private static final String RESPONSE = """
            {
              "model": "jev-latest",
              "answers": {
                "intent": {"type": "choice", "choice": "工作任务", "confidence": 0.92,
                           "probabilities": {"工作任务": 0.92, "联网查询": 0.03, "闲聊问答": 0.05}},
                "complexity": {"type": "score", "score": 0.4, "confidence": 0.66,
                               "legend": {"0": "单轮可答", "1": "需要多步骤", "2": "需要研究分析"},
                               "probabilities": {"0": 0.6, "1": 0.0, "2": 0.4}}
              },
              "usage": {"input_tokens": 210, "output_tokens": 30}
            }
            """;

    @Test
    void parsesAnswersIntoDecision() throws Exception {
        IntentDecision decision = router(true, "key").parse(RESPONSE, 88);
        assertThat(decision.intent()).isEqualTo("工作任务");
        assertThat(decision.intentConfidence()).isEqualTo(0.92);
        assertThat(decision.complexity()).isEqualTo(0.4);
        assertThat(decision.complexityConfidence()).isEqualTo(0.66);
        assertThat(decision.latencyMs()).isEqualTo(88);
    }

    @Test
    void buildRequestCarriesStateAndTwoTypedQuestions() {
        var request = router(true, "key").buildRequest("帮我把事故数据整理成表格");
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(request);
        assertThat(json.path("model").asText()).isEqualTo("jev-latest");
        assertThat(json.path("state").asText()).isEqualTo("帮我把事故数据整理成表格");
        assertThat(json.path("questions").path("intent").path("type").asText()).isEqualTo("choice");
        assertThat(json.path("questions").path("intent").path("criteria")).hasSize(3);
        assertThat(json.path("questions").path("intent").path("criteria").has("工作任务")).isTrue();
        assertThat(json.path("questions").path("intent").path("criteria").has("知识库问答")).isFalse();
        assertThat(json.path("questions").path("complexity").path("criteria")).hasSize(3);
        assertThat(json.path("questions").has("needs_knowledge")).isFalse();
    }

    @Test
    void disabledOrKeylessRouterClassifiesNothing() {
        assertThat(router(false, "key").classify("你好")).isEmpty();
        assertThat(router(true, "").classify("你好")).isEmpty();
        assertThat(router(true, "   ").classify("你好")).isEmpty();
        assertThat(router(true, "key").classify("  ")).isEmpty();
        assertThat(router(true, "key").classify(null)).isEmpty();
    }

    @Test
    void unreachableEndpointFallsBackToEmpty() {
        JevIntentRouter unreachable = new JevIntentRouter(true, "http://127.0.0.1:1", "key", "jev-latest", 300);
        Optional<IntentDecision> decision = unreachable.classify("你好");
        assertThat(decision).isEmpty();
    }

    @Test
    void recommendationFollowsDecisionTable() {
        assertThat(new IntentDecision("联网查询", 0.8, 1.2, 0.6, 10).recommend())
                .isEqualTo(IntentDecision.Recommendation.FALLBACK);
        assertThat(new IntentDecision("闲聊问答", 0.9, 0.1, 0.8, 10).recommend())
                .isEqualTo(IntentDecision.Recommendation.SHORT_CIRCUIT_CANDIDATE);
        assertThat(new IntentDecision("闲聊问答", 0.9, 0.8, 0.8, 10).recommend())
                .isEqualTo(IntentDecision.Recommendation.FALLBACK);
        assertThat(new IntentDecision("工作任务", 0.9, 1.5, 0.7, 10).recommend())
                .isEqualTo(IntentDecision.Recommendation.FALLBACK);
    }

    @Test
    void applyToPreservesBothUserSwitches() {
        IntentDecision web = new IntentDecision("联网查询", 0.8, 1.2, 0.6, 10);
        assertThat(web.applyTo(false, false).knowledgeSearch()).isFalse();
        assertThat(web.applyTo(true, false).knowledgeSearch()).isTrue();
        assertThat(web.applyTo(true, false).webSearch()).isFalse();
        assertThat(web.applyTo(false, true).webSearch()).isTrue();
        assertThat(web.applyTo(false, false).action()).contains("维持用户开关");

        IntentDecision chat = new IntentDecision("闲聊问答", 0.9, 0.1, 0.8, 10);
        assertThat(chat.applyTo(false, false).knowledgeSearch()).isFalse();
        assertThat(chat.applyTo(false, false).action()).contains("闲聊短路候选");

        IntentDecision work = new IntentDecision("工作任务", 0.55, 1.5, 0.4, 10);
        assertThat(work.applyTo(true, false).knowledgeSearch()).isTrue();
        assertThat(work.applyTo(false, false).knowledgeSearch()).isFalse();
        assertThat(work.applyTo(false, false).action()).contains("维持用户开关");
    }

    @Test
    void summaryFormatsActionAndLatencyWithoutKnowledgeScore() {
        IntentDecision work = new IntentDecision("工作任务", 0.92, 0.4, 0.66, 88);
        String summary = work.summary(work.applyTo(false, false).action());
        assertThat(summary).contains("意图：工作任务").contains("0.92").contains("维持用户开关").contains("88ms")
                .doesNotContain("知识库");
    }
}
