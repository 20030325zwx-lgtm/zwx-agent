package com.zwx.zwxagent.creative.domain;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class ParameterSchemaTest {

    private final ParameterSchema schema = ParameterSchema.dashscopeTextToImage();

    @Test
    void acceptsValidOptionalParameters() {
        Assertions.assertTrue(schema.validate(Map.of()).isEmpty());
        Assertions.assertTrue(schema.validate(Map.of("size", "1024*1024")).isEmpty());
        Assertions.assertTrue(schema.validate(Map.of("size", "1280*720", "watermark", true)).isEmpty());
    }

    @Test
    void rejectsUnknownKeys() {
        List<String> violations = schema.validate(Map.of("seed", 1, "negativePrompt", "模糊"));
        Assertions.assertEquals(2, violations.size());
        Assertions.assertTrue(violations.get(0).contains("不支持的参数"));
    }

    @Test
    void rejectsWrongTypeAndUnsupportedValues() {
        Assertions.assertFalse(schema.validate(Map.of("size", 1024)).isEmpty());
        Assertions.assertFalse(schema.validate(Map.of("size", "2000*2000")).isEmpty());
        Assertions.assertFalse(schema.validate(Map.of("watermark", "yes")).isEmpty());
    }
}
