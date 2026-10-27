package com.zwx.zwxagent.chat.conversation;

import java.time.Instant;

public record AgentConversationSummary(String id, String title, String projectId, Instant createdAt, Instant updatedAt) {
}
