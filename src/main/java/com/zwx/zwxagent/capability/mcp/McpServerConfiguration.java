package com.zwx.zwxagent.capability.mcp;

import java.time.Instant;
import java.util.List;

public record McpServerConfiguration(Long id, String name, String endpoint, boolean enabled,
                                     String authType, String authHeaderName, String secretHint,
                                     boolean hasSecret, List<String> allowedTools,
                                     Instant createdAt, Instant updatedAt) {
}
