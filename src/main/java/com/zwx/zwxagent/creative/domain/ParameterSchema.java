package com.zwx.zwxagent.creative.domain;

import java.util.List;
import java.util.Map;

/** 模型参数 schema（media_model_profile.parameter_schema 的领域表示）。 */
public record ParameterSchema(String operation, List<ParameterSpec> parameters) {

    public ParameterSchema {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    public static final String IMAGE_GENERATION = "text_to_image";

    /**
     * 文生图 P1 参数 schema：提示词是 effectivePrompt（独立校验），这里只声明可选参数
     * size/watermark（模型真实范围见模型契约文档；n 固定 1 不开放）。
     */
    public static ParameterSchema dashscopeTextToImage() {
        return new ParameterSchema(IMAGE_GENERATION, List.of(
                new ParameterSpec("size", "string", false,
                        List.of("1024*1024", "720*1280", "1280*720", "768*1152", "1152*768"), null, null),
                new ParameterSpec("watermark", "boolean", false, List.of(), null, null)));
    }

    /**
     * OpenAI images 兼容（中转站）参数 schema：size 是自由 WxH 字符串、不限枚举——
     * 中转站代理的模型（flux/sd/seedream/gpt-image 等）各有尺寸范围，闭集会误拒；
     * n 固定 1 不开放，watermark 为 DashScope 专有。
     */
    public static ParameterSchema openAiImages() {
        return new ParameterSchema(IMAGE_GENERATION, List.of(
                new ParameterSpec("size", "string", false, List.of(), 2L, 32L)));
    }

    /** wanx2.1 系列提示词上限（模型契约文档 §3.1）。 */
    public static final int MODEL_PROMPT_MAX = 500;

    /**
     * 校验输入参数：未知键、缺必填、类型/取值越界都算违规（BR07）。
     * 返回违规描述列表，空列表＝通过。
     */
    public List<String> validate(Map<String, Object> input) {
        List<String> violations = new java.util.ArrayList<>();
        Map<String, Object> safe = input == null ? Map.of() : input;
        for (String key : safe.keySet()) {
            if (parameters.stream().noneMatch(spec -> spec.name().equals(key))) {
                violations.add("不支持的参数：" + key);
            }
        }
        for (ParameterSpec spec : parameters) {
            Object value = safe.get(spec.name());
            if (value == null || (value instanceof String text && text.isBlank())) {
                if (spec.required()) violations.add("缺少必填参数：" + spec.name());
                continue;
            }
            switch (spec.type()) {
                case "string" -> {
                    if (!(value instanceof String text)) {
                        violations.add("参数 " + spec.name() + " 应为字符串");
                        continue;
                    }
                    if (spec.min() != null && text.length() < spec.min()) {
                        violations.add("参数 " + spec.name() + " 长度不足 " + spec.min());
                    }
                    if (spec.max() != null && text.length() > spec.max()) {
                        violations.add("参数 " + spec.name() + " 超过最大长度 " + spec.max());
                    }
                    if (!spec.allowedValues().isEmpty() && !spec.allowedValues().contains(text)) {
                        violations.add("参数 " + spec.name() + " 取值不支持：" + text);
                    }
                }
                case "integer" -> {
                    if (!(value instanceof Number number) || number.longValue() != number.doubleValue()) {
                        violations.add("参数 " + spec.name() + " 应为整数");
                        continue;
                    }
                    long numeric = number.longValue();
                    if (spec.min() != null && numeric < spec.min()) {
                        violations.add("参数 " + spec.name() + " 不能小于 " + spec.min());
                    }
                    if (spec.max() != null && numeric > spec.max()) {
                        violations.add("参数 " + spec.name() + " 不能大于 " + spec.max());
                    }
                }
                case "boolean" -> {
                    if (!(value instanceof Boolean)) violations.add("参数 " + spec.name() + " 应为布尔值");
                }
                default -> violations.add("参数 " + spec.name() + " 类型未声明：" + spec.type());
            }
        }
        return violations;
    }
}
