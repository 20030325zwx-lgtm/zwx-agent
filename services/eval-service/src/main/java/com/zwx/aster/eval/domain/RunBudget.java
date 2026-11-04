package com.zwx.aster.eval.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 实验预算护栏（eval_experiment.budget）：控制跑多少例、花多少钱、judge 抽多少。
 * 默认不设上限（maxCost=null），但仍然「失败样本 100% judge、通过样本按比例抽」。
 */
public record RunBudget(int maxCases, BigDecimal maxCost, double judgeSampleRate) {

    public static RunBudget from(Map<String, Object> raw) {
        if (raw == null) raw = Map.of();
        int maxCases = raw.get("maxCases") instanceof Number n ? Math.max(n.intValue(), 0) : 0;
        BigDecimal maxCost = raw.get("maxCost") instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null;
        double rate = raw.get("judgeSampleRate") instanceof Number n ? n.doubleValue() : 1.0;
        if (rate < 0) rate = 0;
        if (rate > 1) rate = 1;
        return new RunBudget(maxCases, maxCost, rate);
    }

    /** 通过样本的 judge 抽样：按用例 ID 稳定散列，同一用例每次结果一致（可复现）。 */
    public boolean judgeForPassed(String caseExternalId) {
        if (judgeSampleRate >= 1.0) return true;
        if (judgeSampleRate <= 0.0) return false;
        int bucket = Math.floorMod(String.valueOf(caseExternalId).hashCode(), 1000);
        return bucket < (int) Math.round(judgeSampleRate * 1000);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        if (maxCases > 0) map.put("maxCases", maxCases);
        if (maxCost != null) map.put("maxCost", maxCost);
        map.put("judgeSampleRate", judgeSampleRate);
        return map;
    }
}
