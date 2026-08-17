package com.zwx.zwxagent.conversation;

import java.time.Instant;

public record AgentConversationMessage(String role, String content, String executionRunId, Instant createdAt) {
}
