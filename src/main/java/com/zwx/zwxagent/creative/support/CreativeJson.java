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
}
