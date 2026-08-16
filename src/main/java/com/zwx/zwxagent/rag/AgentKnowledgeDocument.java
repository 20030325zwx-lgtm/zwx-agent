package com.zwx.zwxagent.rag;

import java.time.Instant;

public record AgentKnowledgeDocument(String id, String tenantId, String agentKey, String filename, String status,
                                     int chunkCount, String errorMessage, Instant createdAt, Instant completedAt) {
}
