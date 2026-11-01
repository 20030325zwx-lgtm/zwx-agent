package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 镜头候选：某镜头某次得到的可选素材（生成回写或显式上传）。
 * prompt_text 冻结提交时的文本，供回看；生成成功不自动选定。
 */
public record VideoShotCandidateRecord(UUID id, UUID projectId, String tenantId, String userId,
                                       UUID shotId, UUID assetId, UUID jobId, String source,
                                       String promptText, String status,
                                       Instant createdAt, Instant updatedAt) {
}
