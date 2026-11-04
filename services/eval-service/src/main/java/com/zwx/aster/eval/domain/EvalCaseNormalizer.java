package com.zwx.aster.eval.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 导入用例的规范化：把四类既有评测集 schema（task-eval-v1.json / knowledge-intent-eval-v1.json /
 * prompt 手写集）统一为「case payload」。静态纯函数，便于单测。
 */
public final class EvalCaseNormalizer {

    private EvalCaseNormalizer() {
    }

    /** 按字段特征推断评估器类型：expectedIntent→intent；documentId→retrieval；task→task；input→prompt。 */
    public static EvalType inferType(Map<String, Object> raw) {
        if (raw.get("expectedIntent") != null) return EvalType.INTENT;
        if (raw.get("documentId") != null || raw.get("evidence") instanceof Map) return EvalType.RETRIEVAL;
        if (raw.get("task") != null) return EvalType.TASK;
        if (raw.get("input") != null) return EvalType.PROMPT;
        throw new IllegalArgumentException("无法识别用例类型：需要 expectedIntent / documentId(evidence) / task / input 之一");
    }

    /** 规范化单个原始用例；externalId 缺失时按序号补齐。 */
    public static NormalizedCase normalize(Map<String, Object> raw, int index) {
        EvalType type = inferType(raw);
        String externalId = stringOf(raw, "id");
        if (externalId == null || externalId.isBlank()) {
            externalId = "case-" + String.format(Locale.ROOT, "%03d", index + 1);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        String source = stringOf(raw, "source");
        switch (type) {
            case INTENT -> {
                payload.put("question", stringOf(raw, "question"));
                payload.put("expectedIntent", stringOf(raw, "expectedIntent"));
            }
            case RETRIEVAL -> {
                payload.put("question", stringOf(raw, "question"));
                payload.put("agentKey", stringOf(raw, "agentKey"));
                String documentId = stringOf(raw, "documentId");
                if (documentId == null && raw.get("evidence") instanceof Map<?, ?> evidence) {
                    documentId = String.valueOf(evidence.get("documentId"));
                    Object filename = evidence.get("filename");
                    if (filename != null) payload.put("filename", String.valueOf(filename));
                }
                payload.put("documentId", documentId);
                if (raw.get("expectedPoints") != null) payload.put("expectedPoints", raw.get("expectedPoints"));
            }
            case TASK -> {
                payload.put("task", stringOf(raw, "task"));
                payload.put("web_search", Boolean.TRUE.equals(raw.getOrDefault("web_search", raw.get("webSearch"))));
                payload.put("knowledge", Boolean.TRUE.equals(raw.getOrDefault("knowledge", raw.get("knowledgeSearch"))));
                Object expect = raw.get("expect");
                payload.put("expect", expect == null ? Map.of() : expect);
            }
            case PROMPT -> {
                payload.put("system", stringOf(raw, "system"));
                payload.put("input", stringOf(raw, "input"));
                if (raw.get("reference") != null) payload.put("reference", raw.get("reference"));
                Object expect = raw.get("expect");
                payload.put("expect", expect == null ? Map.of() : expect);
                Object judge = raw.get("judge");
                payload.put("judge", judge == null ? Map.of() : judge);
            }
        }
        String notes = stringOf(raw, "notes");
        if (notes != null && !notes.isBlank()) payload.put("notes", notes);
        return new NormalizedCase(externalId, type, payload,
                source == null || source.isBlank() ? "import" : source);
    }

    private static String stringOf(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public record NormalizedCase(String externalId, EvalType evalType,
                                 Map<String, Object> payload, String source) {
    }
}
