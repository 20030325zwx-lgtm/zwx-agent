package com.zwx.zwxagent.rag;

import java.time.Instant;

public record AgentKnowledgeDocument(String id, String tenantId, String agentKey, String filename, String status,
                                     int chunkCount, String errorMessage, Instant createdAt, Instant completedAt,
                                     String logicalKey, Integer versionNo, String lifecycleStatus,
                                     String contentSha256, Instant publishedAt, Instant archivedAt, String createdBy) {

    /** 兼容旧调用点：不含版本治理字段。 */
    public AgentKnowledgeDocument(String id, String tenantId, String agentKey, String filename, String status,
                                  int chunkCount, String errorMessage, Instant createdAt, Instant completedAt) {
        this(id, tenantId, agentKey, filename, status, chunkCount, errorMessage, createdAt, completedAt,
                null, null, null, null, null, null, null);
    }
}
