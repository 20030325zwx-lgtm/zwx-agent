package com.zwx.zwxagent.platform.intent;

/**
 * 一次意图识别的结论（Jev System One 返回 + 延迟）。
 * recommend() 是与实现无关的决策策略（阈值见 docs/design/2026-10-25-jev-intent-routing.md §4）；
 * 批 0 只消费 summary() 做观察展示，批 1 起消费 recommend()。
 */
public record IntentDecision(String intent, double intentConfidence, double complexity,
                             double complexityConfidence, double needsKnowledge, long latencyMs) {

    public enum Recommendation { AUTO_KNOWLEDGE, AUTO_WEB, SHORT_CIRCUIT_CANDIDATE, FALLBACK }

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

    public String summary() {
        Recommendation recommendation = recommend();
        String action = switch (recommendation) {
            case AUTO_KNOWLEDGE -> "建议自动开启知识库检索";
            case AUTO_WEB -> "建议自动开启联网搜索";
            case SHORT_CIRCUIT_CANDIDATE -> "短路候选（未启用）";
            case FALLBACK -> "维持现状（完整图执行）";
        };
        return "意图：" + intent + "（置信 " + String.format("%.2f", intentConfidence) + "）"
                + " · 复杂度 " + String.format("%.1f", complexity)
                + " · 需要知识库 " + String.format("%.2f", needsKnowledge)
                + " · " + action
                + "（观察模式，不改变行为，耗时 " + latencyMs + "ms）";
    }
}
