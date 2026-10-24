package com.zwx.zwxagent.knowledge.document;

import java.time.Instant;

public record AgentKnowledgeDocument(String id, String tenantId, String agentKey, String filename, String status,
                                     int chunkCount, String errorMessage, Instant createdAt, Instant completedAt,
                                     String logicalKey, Integer versionNo, String lifecycleStatus,
                                     String contentSha256, Instant publishedAt, Instant archivedAt, String createdBy,
                                     String categoryId, String reviewNote) {

    /** 兼容旧调用点：不含版本治理与分类字段。 */
    public AgentKnowledgeDocument(String id, String tenantId, String agentKey, String filename, String status,
                                  int chunkCount, String errorMessage, Instant createdAt, Instant completedAt) {
        this(id, tenantId, agentKey, filename, status, chunkCount, errorMessage, createdAt, completedAt,
                null, null, null, null, null, null, null, null, null);
    }

    /** 兼容不含分类字段的版本治理调用点。 */
    public AgentKnowledgeDocument(String id, String tenantId, String agentKey, String filename, String status,
                                  int chunkCount, String errorMessage, Instant createdAt, Instant completedAt,
                                  String logicalKey, Integer versionNo, String lifecycleStatus,
                                  String contentSha256, Instant publishedAt, Instant archivedAt, String createdBy) {
        this(id, tenantId, agentKey, filename, status, chunkCount, errorMessage, createdAt, completedAt,
                logicalKey, versionNo, lifecycleStatus, contentSha256, publishedAt, archivedAt, createdBy, null, null);
    }
}
