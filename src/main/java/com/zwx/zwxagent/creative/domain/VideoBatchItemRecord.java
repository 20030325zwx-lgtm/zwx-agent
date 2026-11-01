package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 批次子项：固定镜头 + 候选槽位。PENDING_SUBMIT 待提交 → SUBMITTED 在途 →
 * DONE 成功回写 / FAILED 失败（可重试，attempt+1 回待提交）/ SKIPPED 停止时未提交。
 */
public record VideoBatchItemRecord(UUID id, UUID projectId, String tenantId, String userId,
                                   UUID batchId, UUID shotId, String status,
                                   UUID candidateId, UUID jobId, int attempt, String error,
                                   Instant createdAt, Instant updatedAt) {
}
