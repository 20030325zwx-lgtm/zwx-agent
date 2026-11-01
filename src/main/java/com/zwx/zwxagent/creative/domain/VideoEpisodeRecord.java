package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.UUID;

/** 剧集（P1）：稳定 ID + 顺序号；剧本草稿/确认状态在本表。 */
public record VideoEpisodeRecord(UUID id, UUID projectId, String tenantId, String userId,
                                 int episodeNumber, String title, String goal, String synopsis,
                                 String script, String status,
                                 Instant createdAt, Instant updatedAt) {

    public boolean confirmed() {
        return "CONFIRMED".equals(status);
    }
}
