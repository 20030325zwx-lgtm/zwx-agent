package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 镜头：审核与重做的最小创作单位；字段编辑（修订）与生成结果（候选）分离。
 * version 乐观锁：更新/选片必须携带 expectedVersion，防两处同时编辑互相覆盖。
 */
public record VideoShotRecord(UUID id, UUID projectId, String tenantId, String userId,
                              UUID sceneId, int shotNumber,
                              String purpose, String action, String camera, String dialogue,
                              String continuity, int durationTargetSeconds, String promptText,
                              String status, long version,
                              Instant createdAt, Instant updatedAt,
                              String styleCode, java.util.List<UUID> referenceAssetIds) {

    /** 首帧参考：最多 1 个；空列表 = 文生视频。 */
    public UUID firstReference() {
        return referenceAssetIds == null || referenceAssetIds.isEmpty() ? null : referenceAssetIds.getFirst();
    }

    public boolean confirmedReady() {
        return ShotStatus.READY.name().equals(status) || ShotStatus.GENERATING.name().equals(status)
                || ShotStatus.PENDING_SELECTION.name().equals(status)
                || ShotStatus.SELECTED.name().equals(status);
    }
}
