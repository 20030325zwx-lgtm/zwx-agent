package com.zwx.zwxagent.capability.mcp;

import java.time.Instant;

public record McpServerConfiguration(Long id, String name, String endpoint, boolean enabled,
                                     Instant createdAt, Instant updatedAt) {
}
