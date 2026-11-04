package com.zwx.aster.eval.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 被测对象声明（experiment.target）。这是「实验 = 数据集版本 × 被测对象」里的那个变量。
 *
 * <p>四类评估器能接受的对象不同：INTENT/RETRIEVAL/TASK 的执行在主站，只能测当前线上配置（live）；
 * PROMPT 直连模型服务，可以换模型或换 Prompt 版本。服务端在创建实验时强校验，
 * 避免出现「看起来能测、其实没变」的假对比。</p>
 */
public record TargetSpec(Kind kind, ModelSpec model, PromptRef prompt, AgentSpec agent, Map<String, Object> params) {

    public enum Kind {
        /** 当前线上配置（主站内部 API 执行）。 */
        LIVE,
        /** 指定模型（仅 PROMPT）。 */
        MODEL,
        /** 指定 Prompt 资产版本（仅 PROMPT，可同时指定模型）。 */
        PROMPT
    }

    public record ModelSpec(String provider, String modelName, Double temperature) {
    }

    public record PromptRef(String assetKey, Integer versionNo) {
    }

    public record AgentSpec(String agentKey) {
    }

    public static TargetSpec live() {
        return new TargetSpec(Kind.LIVE, null, null, null, Map.of());
    }

    /** 解析请求体中的 target；缺省为 live。类型相关的合法性由调用方按数据集评估器校验。 */
    @SuppressWarnings("unchecked")
    public static TargetSpec from(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return live();
        }
        Kind kind = parseKind(raw.get("kind"));
        Map<String, Object> modelRaw = raw.get("model") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        String modelName = trimmed(modelRaw.get("modelName"));
        ModelSpec model = modelName == null ? null : new ModelSpec(
                trimmedOrDefault(modelRaw.get("provider"), "dashscope"), modelName,
                number(modelRaw.get("temperature")));

        PromptRef prompt = null;
        Object promptRaw = raw.get("prompt");
        if (promptRaw instanceof String ref && !ref.isBlank()) {
            prompt = parsePromptRef(ref);
        } else if (promptRaw instanceof Map<?, ?> map) {
            String assetKey = trimmed(map.get("assetKey"));
            Integer versionNo = number(map.get("versionNo")) == null ? null : number(map.get("versionNo")).intValue();
            if (assetKey == null) throw new IllegalArgumentException("target.prompt.assetKey 不能为空");
            prompt = new PromptRef(assetKey, versionNo);
        }

        AgentSpec agent = null;
        if (raw.get("agent") instanceof Map<?, ?> agentMap) {
            String agentKey = trimmed(agentMap.get("agentKey"));
            if (agentKey != null) agent = new AgentSpec(agentKey);
        }
        Map<String, Object> params = raw.get("params") instanceof Map<?, ?> paramsMap
                ? new LinkedHashMap<>((Map<String, Object>) paramsMap) : Map.of();

        // 友好归一：给了 model/prompt 却没写 kind 时，按内容推断（比强迫用户背枚举更顺手）
        if (kind == Kind.LIVE) {
            if (prompt != null) kind = Kind.PROMPT;
            else if (model != null) kind = Kind.MODEL;
        }
        TargetSpec spec = new TargetSpec(kind, model, prompt, agent, params);
        spec.validateBasic();
        return spec;
    }

    /** 与评估器无关的基础校验。 */
    private void validateBasic() {
        switch (kind) {
            case LIVE -> {
                if (model != null || prompt != null) {
                    throw new IllegalArgumentException("target.kind=live 不接受 model/prompt");
                }
            }
            case MODEL -> {
                if (model == null) throw new IllegalArgumentException("target.kind=model 需要 target.model.modelName");
                if (prompt != null) {
                    throw new IllegalArgumentException("target.kind=model 不接受 prompt；要指定 Prompt 版本请用 kind=prompt");
                }
            }
            case PROMPT -> {
                if (prompt == null) {
                    throw new IllegalArgumentException("target.kind=prompt 需要 target.prompt（\"key@v2\" 或 {assetKey,versionNo}）");
                }
            }
        }
    }

    /** 校验「这个评估器能不能测这个对象」。 */
    public void validate(EvalType evalType) {
        if (evalType != EvalType.PROMPT && kind != Kind.LIVE) {
            throw new IllegalArgumentException(evalType.lower() + " 评估器在主站执行，只能测当前线上配置（target.kind=live）；"
                    + "要对比模型或 Prompt，请用 prompt 数据集");
        }
        if (evalType != EvalType.PROMPT && model != null) {
            throw new IllegalArgumentException(evalType.lower() + " 评估器不接受 target.model；换模型请用 prompt 数据集");
        }
        validateBasic();
    }

    /** 落库前的快照（不含 Prompt 正文，正文由 TargetResolver 解析后补入）。 */
    public Map<String, Object> toSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("kind", kind.name().toLowerCase());
        if (model != null) {
            Map<String, Object> modelMap = new LinkedHashMap<>();
            modelMap.put("provider", model.provider());
            modelMap.put("modelName", model.modelName());
            if (model.temperature() != null) modelMap.put("temperature", model.temperature());
            snapshot.put("model", modelMap);
        }
        if (prompt != null) {
            Map<String, Object> promptMap = new LinkedHashMap<>();
            promptMap.put("assetKey", prompt.assetKey());
            if (prompt.versionNo() != null) promptMap.put("versionNo", prompt.versionNo());
            snapshot.put("prompt", promptMap);
        }
        if (agent != null) snapshot.put("agent", Map.of("agentKey", agent.agentKey()));
        if (params != null && !params.isEmpty()) snapshot.put("params", params);
        return snapshot;
    }

    /** 供展示的一行描述，如 "qwen-max × task-planner@v3"。 */
    public String describe() {
        return switch (kind) {
            case LIVE -> "当前线上配置";
            case MODEL -> model == null ? "指定模型" : model.modelName();
            case PROMPT -> (prompt == null ? "Prompt 资产" : prompt.assetKey()
                    + (prompt.versionNo() == null ? "@latest" : "@v" + prompt.versionNo()))
                    + (model == null ? "" : " × " + model.modelName());
        };
    }

    private static Kind parseKind(Object value) {
        String text = trimmed(value);
        if (text == null) return Kind.LIVE;
        try {
            return Kind.valueOf(text.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("target.kind 只支持 live / model / prompt，收到：" + text);
        }
    }

    private static PromptRef parsePromptRef(String ref) {
        int at = ref.lastIndexOf('@');
        if (at <= 0) {
            return new PromptRef(ref.trim(), null);
        }
        String key = ref.substring(0, at).trim();
        String version = ref.substring(at + 1).trim();
        if (version.startsWith("v") || version.startsWith("V")) version = version.substring(1);
        try {
            return new PromptRef(key, Integer.valueOf(version));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Prompt 引用格式应为 key@v2，收到：" + ref);
        }
    }

    private static String trimmed(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String trimmedOrDefault(Object value, String fallback) {
        String text = trimmed(value);
        return text == null ? fallback : text;
    }

    private static Double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value == null) return null;
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
