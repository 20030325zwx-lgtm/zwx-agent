package com.zwx.zwxagent.execution;

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
                       String input,
                       String historyContext,
                       String knowledgeContext,
                       String ragTraceJson,
                       GraphCheckpoint checkpoint,
                       String errorMessage,
                       int resumeCount,
                       Instant createdAt,
                       Instant updatedAt) {

    public boolean resumable() {
        return "INTERRUPTED".equals(status) || "FAILED".equals(status)
                || ("RUNNING".equals(status) && checkpoint != null && checkpoint.lastNode() != null);
    }

    public String displayAnswer() {
        if (checkpoint == null) return "";
        if (checkpoint.finalAnswer() != null && !checkpoint.finalAnswer().isBlank()) return checkpoint.finalAnswer();
        return checkpoint.partialAnswer() == null ? "" : checkpoint.partialAnswer();
    }
}
