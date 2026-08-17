package com.zwx.zwxagent.execution;

import java.time.Instant;
import java.util.Map;

public record AgentExecutionEvent(int sequence, String phase, String summary, Map<String, Object> detail, Instant createdAt) {
}
