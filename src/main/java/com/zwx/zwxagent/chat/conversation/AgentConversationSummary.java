package com.zwx.zwxagent.chat.conversation;

import java.time.Instant;

public record AgentConversationSummary(String id, String title, Instant createdAt, Instant updatedAt) {
}
