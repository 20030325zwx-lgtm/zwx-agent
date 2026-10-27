package com.zwx.zwxagent.platform.intent;

/**
 * 一次意图识别的结论（Jev System One / qwen 对照返回 + 延迟）。
 * 知识库与联网是否启用完全由用户开关决定，意图识别只负责分类和闲聊短路。
 */
public record IntentDecision(String intent, double intentConfidence, double complexity,
                             double complexityConfidence, long latencyMs) {

    public enum Recommendation { SHORT_CIRCUIT_CANDIDATE, FALLBACK }

    /** 开关调整结果：调整后的有效开关 + 面向用户的透明说明。 */
    public record SwitchAdjustment(boolean knowledgeSearch, boolean webSearch, String action) {
    }

    static final double SHORT_CIRCUIT_CONFIDENCE = 0.85;

    public Recommendation recommend() {
        if ("闲聊问答".equals(intent) && intentConfidence >= SHORT_CIRCUIT_CONFIDENCE && complexity <= 0.5) {
            return Recommendation.SHORT_CIRCUIT_CANDIDATE;
        }
        return Recommendation.FALLBACK;
    }

    /** 两个检索开关始终原样保留，意图识别不得覆盖用户选择。 */
    public SwitchAdjustment applyTo(boolean knowledgeSearch, boolean webSearch) {
        return switch (recommend()) {
            case SHORT_CIRCUIT_CANDIDATE -> new SwitchAdjustment(knowledgeSearch, webSearch, "命中闲聊短路候选");
            case FALLBACK -> new SwitchAdjustment(knowledgeSearch, webSearch, "维持用户开关");
        };
    }

    public String summary(String action) {
        return "意图：" + intent + "（置信 " + String.format("%.2f", intentConfidence) + "）"
                + " · 复杂度 " + String.format("%.1f", complexity)
                + " · " + action
                + "（耗时 " + latencyMs + "ms）";
    }
}
