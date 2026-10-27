package com.zwx.zwxagent.platform.workspace;

import java.time.Instant;

public record ProjectWorkspace(
        String id,
        String name,
        String directoryName,
        Instant createdAt,
        Instant updatedAt) {
}
