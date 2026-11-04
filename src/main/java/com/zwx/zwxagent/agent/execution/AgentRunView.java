package com.zwx.zwxagent.agent.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.agent.graph.ActivityEvent;
import com.zwx.zwxagent.agent.graph.GraphCheckpoint;
import com.zwx.zwxagent.agent.graph.PlanStep;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面向工作台的任务视图：把 run checkpoint 中的计划、事件和验收状态一次性暴露给前端。
 *
 * <p>phase/waiting_* 是规范阶段（对齐 dsh-superagent 的 Ticket 阶段模型），status 保留给旧逻辑；
 * assignments 是这次任务的派工历史，promptVersions 是本次 run 使用的提示词版本快照。</p>
 */
public record AgentRunView(String id,
                           String status,
                           String phase,
                           String waitingReason,
                           String waitingDetail,
                           int backgroundJobs,
                           String lastNode,
                           String nextNode,
                           String input,
                           List<PlanStepView> plan,
                           List<String> taskResults,
                           List<ActivityEvent> events,
                           VerificationView verification,
                           boolean canResume,
                           boolean canCancel,
                           boolean canDecideConfirmation,
                           String pendingConfirmation,
                           boolean awaitingPlan,
                           int resumeCount,
                           long userMessageId,
                           Long assistantMessageId,
                           int planSize,
                           int taskResultCount,
                           int partialAnswerLength,
                           int finalAnswerLength,
                           String errorMessage,
                           String tokenUsage,
                           List<AgentRunService.AssignmentView> assignments,
                           Map<String, Integer> promptVersions,
                           Instant createdAt,
                           Instant updatedAt) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Integer>> VERSIONS_TYPE = new TypeReference<>() {
    };

    public record PlanStepView(int index, String id, String title, String role, String roleLabel,
                               String detail, List<String> dependsOn, String acceptance) {
    }

    public record VerificationView(String status, String feedback) {
    }

    public static AgentRunView from(AgentRun run) {
        return from(run, List.of(), Map.of());
    }

    public static AgentRunView from(AgentRun run, List<AgentRunService.AssignmentView> assignments,
                                    Map<String, Integer> promptVersions) {
        GraphCheckpoint checkpoint = run.checkpoint();
        List<PlanStep> plan = checkpoint == null ? List.of() : checkpoint.plan();
        List<String> results = checkpoint == null ? List.of() : checkpoint.taskResults();
        List<ActivityEvent> events = checkpoint == null ? List.of() : checkpoint.events();
        String finalAnswer = checkpoint == null ? null : checkpoint.finalAnswer();
        String partialAnswer = checkpoint == null ? null : checkpoint.partialAnswer();
        String verificationStatus = checkpoint == null ? null : checkpoint.verificationStatus();
        String verificationFeedback = checkpoint == null ? null : checkpoint.verificationFeedback();
        return new AgentRunView(
                run.id(),
                run.status(),
                run.phase() == null ? runStatusPhase(run.status()) : run.phase(),
                nullToEmpty(run.waitingReason()),
                nullToEmpty(run.waitingDetail()),
                run.backgroundJobs(),
                checkpoint == null ? "" : nullToEmpty(checkpoint.lastNode()),
                checkpoint == null ? "" : nullToEmpty(checkpoint.nextNode()),
                run.input(),
                planView(plan),
                List.copyOf(results),
                List.copyOf(events),
                new VerificationView(nullToEmpty(verificationStatus), nullToEmpty(verificationFeedback)),
                run.resumable(),
                "RUNNING".equals(run.status()),
                run.awaitingConfirmation(),
                nullToEmpty(run.pendingConfirmation()),
                "plan_pending".equals(verificationStatus),
                run.resumeCount(),
                run.userMessageId(),
                run.assistantMessageId(),
                plan.size(),
                results.size(),
                partialAnswer == null ? 0 : partialAnswer.length(),
                finalAnswer == null ? 0 : finalAnswer.length(),
                run.errorMessage(),
                run.tokenUsageJson(),
                assignments == null ? List.of() : assignments,
                promptVersions == null ? Map.of() : promptVersions,
                run.createdAt(),
                run.updatedAt());
    }

    /** 旧数据（迁移前没有 phase）按 status 兜底。 */
    private static String runStatusPhase(String status) {
        return RunPhase.derive(status, null, 0).name();
    }

    public static Map<String, Integer> parsePromptVersions(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Integer> parsed = JSON.readValue(json, VERSIONS_TYPE);
            return parsed == null ? Map.of() : new LinkedHashMap<>(parsed);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    public static List<PlanStepView> planView(List<PlanStep> plan) {
        if (plan == null || plan.isEmpty()) return List.of();
        List<PlanStepView> result = new ArrayList<>(plan.size());
        for (int index = 0; index < plan.size(); index++) {
            PlanStep step = plan.get(index);
            result.add(new PlanStepView(index, step.id(), step.title(), step.role().key(),
                    step.role().displayName(), step.detail(), step.dependsOn(), step.acceptance()));
        }
        return List.copyOf(result);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
