package com.zwx.zwxagent.rag;

import java.time.Instant;

public record AgentKnowledgeCategory(String id, String tenantId, String agentKey, String name,
                                     Instant createdAt, String createdBy) {
}
