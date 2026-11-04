package com.zwx.zwxagent.agent.execution;

import com.zwx.zwxagent.agent.graph.GraphCheckpoint;

import java.time.Instant;

public record AgentRun(String id,
                       String conversationId,
                       String tenantId,
                       String userId,
                       String agentKey,
                       long userMessageId,
                       Long assistantMessageId,
                       String status,
                       String phase,
                       String waitingReason,
                       String waitingDetail,
                       int backgroundJobs,
                       Instant backgroundDeadline,
                       String promptVersionsJson,
                       String permissionMode,
                       String input,
                       String historyContext,
                       String knowledgeContext,
                       String ragTraceJson,
                       String tokenUsageJson,
                       String pendingConfirmation,
                       GraphCheckpoint checkpoint,
                       int engineVersion,
                       String errorMessage,
                       int resumeCount,
                       Instant createdAt,
                       Instant updatedAt) {

    /** 是否为 A2 持久化运行底座（engine_version=2）。 */
    public boolean engineV2() {
        return engineVersion >= 2;
    }

    public boolean resumable() {
        return "INTERRUPTED".equals(status) || "FAILED".equals(status)
                || ("RUNNING".equals(status) && checkpoint != null && checkpoint.lastNode() != null);
    }

    /** 是否正在等用户补充信息（askHuman 提问，区别于工具/计划确认）。 */
    public boolean waitingForInput() {
        return WaitingReason.NEED_INPUT == WaitingReason.from(waitingReason);
    }

    public WaitingReason waitingReasonEnum() {
        return WaitingReason.from(waitingReason);
    }

    /** run 是否正挂起等待工具确认（挂起期间 status=INTERRUPTED + pending_confirmation 非空）。 */
    public boolean awaitingConfirmation() {
        return pendingConfirmation != null && !pendingConfirmation.isBlank();
    }

    public String displayAnswer() {
        if (checkpoint == null) return "";
        if (checkpoint.finalAnswer() != null && !checkpoint.finalAnswer().isBlank()) return checkpoint.finalAnswer();
        return checkpoint.partialAnswer() == null ? "" : checkpoint.partialAnswer();
    }
}
