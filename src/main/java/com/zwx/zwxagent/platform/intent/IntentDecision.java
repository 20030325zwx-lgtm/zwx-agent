package com.zwx.zwxagent.platform.intent;

/**
 * 一次意图识别的结论（Jev System One / qwen 对照返回 + 延迟）。
 * recommend() 是与实现无关的决策策略（阈值见 docs/design/2026-10-25-jev-intent-routing.md §4）；
 * applyTo() 把结论落到请求开关上——批 1 起生效：只允许把开关打开，永不关闭用户显式勾选的开关。
 */
public record IntentDecision(String intent, double intentConfidence, double complexity,
                             double complexityConfidence, double needsKnowledge, long latencyMs) {

    public enum Recommendation { AUTO_KNOWLEDGE, AUTO_WEB, SHORT_CIRCUIT_CANDIDATE, FALLBACK }

    /** 开关调整结果：调整后的有效开关 + 面向用户的透明说明。 */
    public record SwitchAdjustment(boolean knowledgeSearch, boolean webSearch, String action) {
    }

    static final double HIGH_CONFIDENCE = 0.7;
    static final double SHORT_CIRCUIT_CONFIDENCE = 0.85;
    static final double KNOWLEDGE_NEED_PROBABILITY = 0.8;

    public Recommendation recommend() {
        boolean highConfidence = intentConfidence >= HIGH_CONFIDENCE;
        if (highConfidence && "知识库问答".equals(intent) || needsKnowledge >= KNOWLEDGE_NEED_PROBABILITY) {
            return Recommendation.AUTO_KNOWLEDGE;
        }
        if (highConfidence && "联网查询".equals(intent)) {
            return Recommendation.AUTO_WEB;
        }
        if ("闲聊问答".equals(intent) && intentConfidence >= SHORT_CIRCUIT_CONFIDENCE && complexity <= 0.5) {
            return Recommendation.SHORT_CIRCUIT_CANDIDATE;
        }
        return Recommendation.FALLBACK;
    }

    /** 批 1 规则：只开不关。用户已勾选的开关视为"维持"，未勾选而意图命中则自动打开并在 action 中明示。 */
    public SwitchAdjustment applyTo(boolean knowledgeSearch, boolean webSearch) {
        return switch (recommend()) {
            case AUTO_KNOWLEDGE -> knowledgeSearch
                    ? new SwitchAdjustment(knowledgeSearch, webSearch, "知识库检索已开启（维持用户勾选）")
                    : new SwitchAdjustment(true, webSearch, "已自动开启知识库检索（原为关闭）");
            case AUTO_WEB -> webSearch
                    ? new SwitchAdjustment(knowledgeSearch, webSearch, "联网搜索已开启（维持用户勾选）")
                    : new SwitchAdjustment(knowledgeSearch, true, "已自动开启联网搜索（原为关闭）");
            case SHORT_CIRCUIT_CANDIDATE -> new SwitchAdjustment(knowledgeSearch, webSearch, "短路候选（批 2 未启用），维持用户开关");
            case FALLBACK -> new SwitchAdjustment(knowledgeSearch, webSearch, "维持用户开关");
        };
    }

    public String summary(String action) {
        return "意图：" + intent + "（置信 " + String.format("%.2f", intentConfidence) + "）"
                + " · 复杂度 " + String.format("%.1f", complexity)
                + " · 需要知识库 " + String.format("%.2f", needsKnowledge)
                + " · " + action
                + "（耗时 " + latencyMs + "ms）";
    }
}
