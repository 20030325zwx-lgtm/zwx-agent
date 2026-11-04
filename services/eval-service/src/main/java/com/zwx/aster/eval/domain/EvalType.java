package com.zwx.aster.eval.domain;

import java.util.Locale;

/** 评测类型：决定评估器与用例 payload schema。 */
public enum EvalType {
    INTENT, RETRIEVAL, PROMPT, TASK;

    public static EvalType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("eval_type is required");
        }
        return EvalType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public String lower() {
        return name().toLowerCase(Locale.ROOT);
    }
}
