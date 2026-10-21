package com.zwx.zwxagent.conversation;

import java.time.Instant;

public record AgentConversationMessage(long id, String role, String content, String executionRunId,
                                       String fileAttachments, String ragRetrievalTrace, Instant createdAt,
                                       String status, String imageObjectKeys) {

    /** 兼容不包含图片字段的旧调用点。 */
    public AgentConversationMessage(long id, String role, String content, String executionRunId,
                                    String fileAttachments, String ragRetrievalTrace, Instant createdAt,
                                    String status) {
        this(id, role, content, executionRunId, fileAttachments, ragRetrievalTrace, createdAt, status, "[]");
    }
}
