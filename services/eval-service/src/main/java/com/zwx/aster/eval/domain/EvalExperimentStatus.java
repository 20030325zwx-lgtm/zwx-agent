package com.zwx.aster.eval.domain;

import java.util.Locale;
import java.util.Set;

/** 实验状态机：queued → running → 终态；cancelled 可由 queued/running 直接进入。 */
public enum EvalExperimentStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    public static final Set<String> ACTIVE = Set.of("queued", "running");

    public static EvalExperimentStatus parse(String value) {
        return EvalExperimentStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public String lower() {
        return name().toLowerCase(Locale.ROOT);
    }
}
