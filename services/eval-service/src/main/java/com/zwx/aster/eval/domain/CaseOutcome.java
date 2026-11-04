package com.zwx.aster.eval.domain;

import java.math.BigDecimal;
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
                          String error,
                          Usage usage) {

    /** token 与成本：拿不到时为 null（不假装是 0）。 */
    public record Usage(Integer inputTokens, Integer outputTokens, BigDecimal cost) {

        public static final Usage EMPTY = new Usage(null, null, null);

        public static Usage of(Integer inputTokens, Integer outputTokens, BigDecimal cost) {
            return new Usage(inputTokens, outputTokens, cost);
        }

        public Usage plus(Usage other) {
            if (other == null) return this;
            return new Usage(sum(inputTokens, other.inputTokens), sum(outputTokens, other.outputTokens),
                    cost == null && other.cost == null ? null
                            : (cost == null ? other.cost : other.cost == null ? cost : cost.add(other.cost)));
        }

        private static Integer sum(Integer left, Integer right) {
            return left == null ? right : right == null ? left : left + right;
        }
    }

    public static CaseOutcome failure(String error, long latencyMs) {
        return new CaseOutcome(false, List.of(
                Map.of("name", "executed", "passed", false, "detail", String.valueOf(error))),
                null, latencyMs, null, null, null, error, Usage.EMPTY);
    }

    public static CaseOutcome skipped(String reason) {
        return new CaseOutcome(null, List.of(
                Map.of("name", "skipped", "passed", false, "detail", String.valueOf(reason))),
                null, 0, null, null, null, reason, Usage.EMPTY);
    }

    public CaseOutcome withUsage(Usage newUsage) {
        return new CaseOutcome(passed, checks, judge, latencyMs, answer, digest, snapshot, error, newUsage);
    }
}
