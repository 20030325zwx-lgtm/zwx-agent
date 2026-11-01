package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.UUID;

/** 剧情场次：一次具体叙事事件；entry_state/exit_state 记录场前/场后差异。 */
public record VideoSceneRecord(UUID id, UUID projectId, String tenantId, String userId,
                               UUID episodeId, int sceneNumber, String title,
                               String locationText, String timeOfDay, String charactersText,
                               String goal, String conflict, String entryState, String exitState,
                               String status, Instant createdAt, Instant updatedAt) {
}
