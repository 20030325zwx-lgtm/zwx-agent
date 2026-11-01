package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.UUID;

/** 地点：可复用场景资源；同一地点可出现在多个剧集/场次。 */
public record VideoLocationRecord(UUID id, UUID projectId, String tenantId, String userId,
                                  String name, String description,
                                  Instant createdAt, Instant updatedAt) {
}
