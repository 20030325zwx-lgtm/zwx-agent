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
                       String permissionMode,
                       String input,
                       String historyContext,
                       String knowledgeContext,
                       String ragTraceJson,
                       String tokenUsageJson,
                       String pendingConfirmation,
                       GraphCheckpoint checkpoint,
                       String errorMessage,
                       int resumeCount,
                       Instant createdAt,
                       Instant updatedAt) {

    public boolean resumable() {
        return "INTERRUPTED".equals(status) || "FAILED".equals(status)
                || ("RUNNING".equals(status) && checkpoint != null && checkpoint.lastNode() != null);
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
