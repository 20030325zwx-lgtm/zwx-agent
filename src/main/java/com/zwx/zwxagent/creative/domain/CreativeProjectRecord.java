package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.UUID;

/** creative_project 行记录。 */
public record CreativeProjectRecord(UUID id, String tenantId, String userId, String name, String description,
                                    CreativeKind kind, ProjectStatus status, long version,
                                    Instant createdAt, Instant updatedAt) {
}
