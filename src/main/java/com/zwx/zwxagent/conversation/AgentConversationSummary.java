package com.zwx.zwxagent.conversation;

import java.time.Instant;

public record AgentConversationSummary(String id, String title, Instant createdAt, Instant updatedAt) {
}
