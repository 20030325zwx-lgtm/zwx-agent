package com.zwx.aster.data.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** data 模块 JSON 入口（含 Instant 的 refs 需 JSR310）。 */
public final class DataJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private DataJson() {
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON 序列化失败", exception);
        }
    }

    public static java.util.Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return new java.util.LinkedHashMap<>();
        try {
            return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON 解析失败", exception);
        }
    }
}
