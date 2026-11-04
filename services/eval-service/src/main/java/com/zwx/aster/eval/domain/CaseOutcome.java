package com.zwx.aster.eval.domain;

import java.util.List;
import java.util.Map;

/** 单例执行结果的中立载体：passed=null 表示跳过（如意图路由未启用）。 */
public record CaseOutcome(Boolean passed,
                          List<Map<String, Object>> checks,
                          Map<String, Object> judge,
                          long latencyMs,
                          String answer,
                          Map<String, Object> digest,
                          Map<String, Object> snapshot,
                          String error) {

    public static CaseOutcome failure(String error, long latencyMs) {
        return new CaseOutcome(false, List.of(
                        Map.of("name", "executed", "passed", false, "detail", error)),
                null, latencyMs, null, null, null, error);
    }

    public static CaseOutcome skipped(String reason) {
        return new CaseOutcome(null, List.of(
                        Map.of("name", "skipped", "passed", false, "detail", reason)),
                null, 0, null, null, null, reason);
    }
}
