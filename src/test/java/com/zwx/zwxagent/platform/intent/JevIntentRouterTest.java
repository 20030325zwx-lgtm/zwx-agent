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
                "intent": {"type": "choice", "choice": "知识库问答", "confidence": 0.92,
                           "probabilities": {"知识库问答": 0.92, "联网查询": 0.03, "工具任务": 0.04, "闲聊问答": 0.01}},
                "complexity": {"type": "score", "score": 0.4, "confidence": 0.66,
                               "legend": {"0": "单轮可答", "1": "需要多步骤", "2": "需要研究分析"},
                               "probabilities": {"0": 0.6, "1": 0.0, "2": 0.4}},
                "needs_knowledge": {"type": "noul", "noul": 0.95}
              },
              "usage": {"input_tokens": 210, "output_tokens": 30}
            }
            """;

    @Test
    void parsesAnswersIntoDecision() throws Exception {
        IntentDecision decision = router(true, "key").parse(RESPONSE, 88);

        assertThat(decision.intent()).isEqualTo("知识库问答");
        assertThat(decision.intentConfidence()).isEqualTo(0.92);
        assertThat(decision.complexity()).isEqualTo(0.4);
        assertThat(decision.complexityConfidence()).isEqualTo(0.66);
        assertThat(decision.needsKnowledge()).isEqualTo(0.95);
        assertThat(decision.latencyMs()).isEqualTo(88);
    }

    @Test
    void buildRequestCarriesStateAndThreeTypedQuestions() throws Exception {
        var request = router(true, "key").buildRequest("帮我把事故数据整理成表格");
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(request);

        assertThat(json.path("model").asText()).isEqualTo("jev-latest");
        assertThat(json.path("state").asText()).isEqualTo("帮我把事故数据整理成表格");
        assertThat(json.path("questions").path("intent").path("type").asText()).isEqualTo("choice");
        assertThat(json.path("questions").path("intent").path("criteria")).hasSize(4);
        assertThat(json.path("questions").path("complexity").path("criteria")).hasSize(3);
        assertThat(json.path("questions").path("needs_knowledge").path("type").asText()).isEqualTo("noul");
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
        assertThat(new IntentDecision("知识库问答", 0.92, 0.4, 0.66, 0.95, 10).recommend())
                .isEqualTo(IntentDecision.Recommendation.AUTO_KNOWLEDGE);
        assertThat(new IntentDecision("联网查询", 0.8, 1.2, 0.6, 0.2, 10).recommend())
                .isEqualTo(IntentDecision.Recommendation.AUTO_WEB);
        assertThat(new IntentDecision("闲聊问答", 0.9, 0.1, 0.8, 0.05, 10).recommend())
                .isEqualTo(IntentDecision.Recommendation.SHORT_CIRCUIT_CANDIDATE);
        // 批 2 收紧：模型仍认为可能需要知识库（needs_knowledge ≥ 0.3）时不短路
        assertThat(new IntentDecision("闲聊问答", 0.9, 0.1, 0.8, 0.45, 10).recommend())
                .isEqualTo(IntentDecision.Recommendation.FALLBACK);
        assertThat(new IntentDecision("工具任务", 0.9, 1.5, 0.7, 0.1, 10).recommend())
                .isEqualTo(IntentDecision.Recommendation.FALLBACK);
        assertThat(new IntentDecision("知识库问答", 0.4, 0.3, 0.5, 0.6, 10).recommend())
                .isEqualTo(IntentDecision.Recommendation.FALLBACK);
        // needs_knowledge 兜底：意图置信不足但知识库需求概率极高，仍建议开检索
        assertThat(new IntentDecision("工具任务", 0.55, 0.2, 0.6, 0.9, 10).recommend())
                .isEqualTo(IntentDecision.Recommendation.AUTO_KNOWLEDGE);
    }

    @Test
    void applyToTurnsOnUncheckedSwitchesAndNeverTurnsOffExplicitOnes() {
        // AUTO_KNOWLEDGE：未勾选 → 自动打开；已勾选 → 维持
        IntentDecision knowledge = new IntentDecision("知识库问答", 0.92, 0.4, 0.66, 0.95, 88);
        IntentDecision.SwitchAdjustment autoOn = knowledge.applyTo(false, true);
        assertThat(autoOn.knowledgeSearch()).isTrue();
        assertThat(autoOn.webSearch()).isTrue();
        assertThat(autoOn.action()).contains("已自动开启知识库检索").contains("原为关闭");
        IntentDecision.SwitchAdjustment kept = knowledge.applyTo(true, false);
        assertThat(kept.knowledgeSearch()).isTrue();
        assertThat(kept.action()).contains("维持用户勾选");

        // AUTO_WEB：未勾选 → 自动打开
        IntentDecision web = new IntentDecision("联网查询", 0.8, 1.2, 0.6, 0.2, 10);
        assertThat(web.applyTo(true, false).webSearch()).isTrue();
        assertThat(web.applyTo(true, false).action()).contains("已自动开启联网搜索");

        // 短路候选与低置信：一律维持用户开关
        assertThat(new IntentDecision("闲聊问答", 0.9, 0.1, 0.8, 0.05, 10).applyTo(false, false).knowledgeSearch()).isFalse();
        assertThat(new IntentDecision("闲聊问答", 0.9, 0.1, 0.8, 0.05, 10).applyTo(false, false).action()).contains("闲聊短路候选");
        assertThat(new IntentDecision("工具任务", 0.55, 1.5, 0.4, 0.3, 10).applyTo(false, false).action()).contains("维持用户开关");
    }

    @Test
    void summaryFormatsActionAndLatency() {
        IntentDecision knowledge = new IntentDecision("知识库问答", 0.92, 0.4, 0.66, 0.95, 88);
        String summary = knowledge.summary(knowledge.applyTo(false, true).action());
        assertThat(summary).contains("意图：知识库问答").contains("0.92").contains("已自动开启知识库检索").contains("88ms");
    }
}
