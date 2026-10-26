package com.zwx.zwxagent.agent.execution;

import com.zwx.zwxagent.agent.graph.ActivityEvent;
import com.zwx.zwxagent.agent.graph.GraphCheckpoint;
import com.zwx.zwxagent.agent.graph.PlanStep;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 面向工作台的任务视图：把 run checkpoint 中的计划、事件和验收状态一次性暴露给前端。
 */
public record AgentRunView(String id,
                           String status,
                           String lastNode,
                           String nextNode,
                           String input,
                           List<PlanStepView> plan,
                           List<String> taskResults,
                           List<ActivityEvent> events,
                           VerificationView verification,
                           boolean canResume,
                           boolean canCancel,
                           int resumeCount,
                           long userMessageId,
                           Long assistantMessageId,
                           int planSize,
                           int taskResultCount,
                           int partialAnswerLength,
                           int finalAnswerLength,
                           String errorMessage,
                           String tokenUsage,
                           Instant createdAt,
                           Instant updatedAt) {

    public record PlanStepView(int index, String title, String role, String roleLabel, String detail) {
    }

    public record VerificationView(String status, String feedback) {
    }

    public static AgentRunView from(AgentRun run) {
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
                checkpoint == null ? "" : nullToEmpty(checkpoint.lastNode()),
                checkpoint == null ? "" : nullToEmpty(checkpoint.nextNode()),
                run.input(),
                planView(plan),
                List.copyOf(results),
                List.copyOf(events),
                new VerificationView(nullToEmpty(verificationStatus), nullToEmpty(verificationFeedback)),
                run.resumable(),
                "RUNNING".equals(run.status()),
                run.resumeCount(),
                run.userMessageId(),
                run.assistantMessageId(),
                plan.size(),
                results.size(),
                partialAnswer == null ? 0 : partialAnswer.length(),
                finalAnswer == null ? 0 : finalAnswer.length(),
                run.errorMessage(),
                run.tokenUsageJson(),
                run.createdAt(),
                run.updatedAt());
    }

    public static List<PlanStepView> planView(List<PlanStep> plan) {
        if (plan == null || plan.isEmpty()) return List.of();
        List<PlanStepView> result = new ArrayList<>(plan.size());
        for (int index = 0; index < plan.size(); index++) {
            PlanStep step = plan.get(index);
            result.add(new PlanStepView(index, step.title(), step.role().key(),
                    step.role().displayName(), step.detail()));
        }
        return List.copyOf(result);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
