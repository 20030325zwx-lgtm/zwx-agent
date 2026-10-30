package com.zwx.zwxagent.creative.domain;

import java.util.List;

/**
 * 参数 schema：单个参数的声明（BR07：仅接受 schema 声明的键）。
 * type 取 string/integer/boolean；allowedValues 非空时取值必须命中。
 */
public record ParameterSpec(String name, String type, boolean required,
                            List<String> allowedValues, Long min, Long max) {

    public ParameterSpec {
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }
}
