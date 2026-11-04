package com.zwx.aster.eval.domain;

import java.util.Map;

/**
 * 实验快照（eval_experiment.candidate）的只读视图：评估器从这里取模型、Prompt 正文与参数。
 * 快照在创建实验时冻结，所以「上周那次实验到底测的是什么」永远可查。
 */
public record TargetView(String kind,
                         String model,
                         Double temperature,
                         String system,
                         String promptRef,
                         String agentKey,
                         Map<String, Object> raw) {

    @SuppressWarnings("unchecked")
    public static TargetView from(Map<String, Object> candidate) {
        if (candidate == null) candidate = Map.of();
        String kind = text(candidate.get("kind"));
        Map<String, Object> model = candidate.get("model") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        Map<String, Object> prompt = candidate.get("prompt") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        Map<String, Object> agent = candidate.get("agent") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        Double temperature = model.get("temperature") instanceof Number n ? n.doubleValue() : null;
        String assetKey = text(prompt.get("assetKey"));
        Object versionNo = prompt.get("versionNo");
        String promptRef = assetKey == null ? null
                : assetKey + (versionNo == null ? "@latest" : "@v" + versionNo);
        return new TargetView(
                kind == null ? "live" : kind,
                text(model.get("modelName")),
                temperature,
                text(prompt.get("system")),
                promptRef,
                text(agent.get("agentKey")),
                candidate);
    }

    public String describe() {
        if ("live".equals(kind)) return "当前线上配置";
        StringBuilder text = new StringBuilder();
        if (promptRef != null) text.append(promptRef);
        if (model != null) text.append(text.isEmpty() ? "" : " × ").append(model);
        return text.isEmpty() ? kind : text.toString();
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
