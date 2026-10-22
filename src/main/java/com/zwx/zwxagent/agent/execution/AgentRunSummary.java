package com.zwx.zwxagent.agent.execution;

import com.zwx.zwxagent.agent.graph.GraphCheckpoint;

import java.time.Instant;

/**
 * 会话内 run 列表的轻量摘要，用于历史会话回填，不携带完整事件列表。
 */
public record AgentRunSummary(String id,
                              String status,
                              String lastNode,
                              String nextNode,
                              int planSize,
                              int taskResultCount,
                              String verificationStatus,
                              boolean canResume,
                              boolean canCancel,
                              int resumeCount,
                              long userMessageId,
                              Long assistantMessageId,
                              Instant createdAt,
                              Instant updatedAt) {

    public static AgentRunSummary from(AgentRun run) {
        GraphCheckpoint checkpoint = run.checkpoint();
        return new AgentRunSummary(
                run.id(),
                run.status(),
                checkpoint == null ? "" : nullToEmpty(checkpoint.lastNode()),
                checkpoint == null ? "" : nullToEmpty(checkpoint.nextNode()),
                checkpoint == null ? 0 : checkpoint.plan().size(),
                checkpoint == null ? 0 : checkpoint.taskResults().size(),
                checkpoint == null ? "" : nullToEmpty(checkpoint.verificationStatus()),
                run.resumable(),
                "RUNNING".equals(run.status()),
                run.resumeCount(),
                run.userMessageId(),
                run.assistantMessageId(),
                run.createdAt(),
                run.updatedAt());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
