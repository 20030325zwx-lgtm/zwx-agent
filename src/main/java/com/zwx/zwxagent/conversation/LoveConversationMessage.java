package com.zwx.zwxagent.conversation;

import java.time.Instant;
import java.util.List;

public record LoveConversationMessage(String role, String content, List<String> imageObjectKeys, Instant createdAt) {
}
