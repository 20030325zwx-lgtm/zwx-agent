package com.zwx.zwxagent.knowledge.document;

import java.time.Instant;

public record AgentKnowledgeCategory(String id, String tenantId, String agentKey, String name,
                                     Instant createdAt, String createdBy) {
}
