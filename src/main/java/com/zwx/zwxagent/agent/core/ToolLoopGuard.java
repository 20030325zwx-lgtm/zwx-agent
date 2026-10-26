package com.zwx.zwxagent.agent.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具循环防护：对每步的工具执行做指纹统计，识别两类循环并给出停机原因。
 * 纯确定性代码，不经过模型判断；run 与 runStream 两条执行路径共用。
 * <p>
 * 两类指纹：
 * - 参数指纹（工具名 + 参数）：同一工具用相同参数反复调用，是参数循环；
 * - 结果指纹（工具名 + 归一化结果）：用于判定一轮是否产出「未见过的有效结果」。
 * 产出新有效结果的轮次视为有进展，豁免参数循环判定并清零空转计数——
 * 允许「重试同参数拿到了新结果」的正当重试场景。
 */
final class ToolLoopGuard {

    /** 同一工具用相同参数的非生产性调用累计达到该次数即判为参数循环。 */
    static final int MAX_SAME_ARGS_CALLS = 3;
    /** 连续没有新有效结果的轮数达到该值即判为空转。 */
    static final int MAX_NO_PROGRESS_ROUNDS = 5;

    private final Map<String, Integer> sameArgsCallCounts = new HashMap<>();
    private final Set<String> observedMeaningfulResults = new HashSet<>();
    private int noProgressRounds = 0;

    /**
     * 喂入一步的全部工具执行；返回非空表示应停止，值为面向用户的停机原因。
     * 空列表（本步未调用工具）不参与任何计数。
     */
    Optional<String> observe(List<BaseAgent.ToolExecution> executions) {
        if (executions == null || executions.isEmpty()) {
            return Optional.empty();
        }
        boolean madeProgress = false;
        String repeatedToolName = null;
        for (BaseAgent.ToolExecution execution : executions) {
            String argsFingerprint = execution.name() + "\n" + normalize(execution.arguments());
            if (sameArgsCallCounts.merge(argsFingerprint, 1, Integer::sum) >= MAX_SAME_ARGS_CALLS) {
                repeatedToolName = execution.name();
            }
            String resultFingerprint = execution.name() + "\n" + normalize(execution.result());
            if (isMeaningful(execution.result()) && observedMeaningfulResults.add(resultFingerprint)) {
                madeProgress = true;
            }
        }
        if (madeProgress) {
            noProgressRounds = 0;
            return Optional.empty();
        }
        if (repeatedToolName != null) {
            return Optional.of("同一工具（" + repeatedToolName + "）已用相同参数重复调用 "
                    + MAX_SAME_ARGS_CALLS + " 次仍未获得新结果");
        }
        noProgressRounds++;
        if (noProgressRounds >= MAX_NO_PROGRESS_ROUNDS) {
            return Optional.of("连续 " + MAX_NO_PROGRESS_ROUNDS + " 轮没有获得新的有效结果");
        }
        return Optional.empty();
    }

    private static boolean isMeaningful(String result) {
        String normalized = normalize(result);
        return !normalized.isEmpty()
                && !normalized.startsWith("error")
                && !normalized.startsWith("search_unavailable")
                && !normalized.contains("失败")
                && !normalized.contains("无法");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
