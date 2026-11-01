package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.UUID;

/** 剧情连续性记录：事实 / 状态 / 伏笔（foreshadowStatus 仅伏笔用）。 */
public record VideoContinuityNoteRecord(UUID id, UUID projectId, String tenantId, String userId,
                                        String kind, String content, String sourceLabel,
                                        String foreshadowStatus,
                                        Instant createdAt, Instant updatedAt) {
}
