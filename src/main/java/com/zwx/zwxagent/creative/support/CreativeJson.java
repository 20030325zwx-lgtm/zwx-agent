package com.zwx.zwxagent.creative.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * creative 模块内部的 JSON 序列化入口。数据库 JSONB 列以字符串参数写入（SQL 中 ::jsonb 转型）。
 * 不在此层做字段校验；校验属于服务层与参数 schema。
 */
public final class CreativeJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private CreativeJson() {
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

    public static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON 反序列化失败", exception);
        }
    }

    public static <T> T fromJson(String json, com.fasterxml.jackson.core.type.TypeReference<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON 反序列化失败", exception);
        }
    }

    /** JSONB 字符串列转字符串列表；空/非法输入返回空列表，不让坏行炸掉整页查询。 */
    public static java.util.List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return java.util.List.of();
        try {
            java.util.List<String> values = MAPPER.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() { });
            return values == null ? java.util.List.of() : values.stream()
                    .filter(value -> value != null && !value.isBlank()).toList();
        } catch (JsonProcessingException exception) {
            return java.util.List.of();
        }
    }

    /** JSONB 字符串列转 Map；空/非法输入返回空 Map（技能正文属内容数据，解析失败按空处理）。 */
    public static java.util.Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return java.util.Map.of();
        try {
            java.util.Map<String, Object> values = MAPPER.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { });
            return values == null ? java.util.Map.of() : values;
        } catch (JsonProcessingException exception) {
            return java.util.Map.of();
        }
    }
}
