package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 成片（P2 最小闭环）：DRAFT 行是可编辑草稿；导出从草稿快照出运行行。
 * items 在导出行上是冻结快照——导出期间继续编辑项目不改变在途成片。
 */
public record VideoFinalCutRecord(UUID id, UUID projectId, String tenantId, String userId,
                                  String status, List<VideoFinalCutItem> items, long itemsVersion,
                                  UUID outputAssetId, Long outputBytes, Long outputDurationMs,
                                  String outputSize, String error,
                                  UUID sourceDraftId, String idempotencyKey,
                                  Instant createdAt, Instant updatedAt) {

    /** 片段：顺序即拼接顺序；assetId 必须是本人项目内已就绪的视频素材。 */
    public record VideoFinalCutItem(UUID shotId, int shotNumber, UUID candidateId, UUID assetId,
                                    String displayName, Long durationMs) {
    }

    public boolean isDraft() {
        return "DRAFT".equals(status);
    }

    public boolean isRunning() {
        return "PREPARING".equals(status) || "PROCESSING".equals(status) || "UPLOADING".equals(status);
    }
}
