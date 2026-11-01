package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.UUID;

/** 角色：跨集复用资源；固定身份（本表）与当场状态（连续性记录）分开。 */
public record VideoCharacterRecord(UUID id, UUID projectId, String tenantId, String userId,
                                   String name, String appearance, String personality, String notes,
                                   Instant createdAt, Instant updatedAt) {
}
