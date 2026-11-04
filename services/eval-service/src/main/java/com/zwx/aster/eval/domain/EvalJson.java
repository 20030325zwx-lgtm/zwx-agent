package com.zwx.aster.eval.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** eval 模块 JSON 入口（含 Instant 快照，必须注册 JSR310）。 */
public final class EvalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private EvalJson() {
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON 序列化失败", exception);
        }
    }

    public static com.fasterxml.jackson.databind.JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON 解析失败", exception);
        }
    }

    public static java.util.Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank() || "null".equals(json.trim())) return new java.util.LinkedHashMap<>();
        try {
            return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON 解析失败", exception);
        }
    }

    /** 解析 JSON 数组（失败或非数组返回空列表，报告聚合不应因脏数据整体失败）。 */
    public static java.util.List<java.util.Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank() || "null".equals(json.trim())) return java.util.List.of();
        try {
            java.util.List<java.util.Map<String, Object>> parsed = MAPPER.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
            return parsed == null ? java.util.List.of() : parsed;
        } catch (JsonProcessingException exception) {
            return java.util.List.of();
        }
    }
}
