package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.UUID;

/** media_job 行记录。 */
public record MediaJobRecord(
        UUID id,
        UUID projectId,
        String tenantId,
        String userId,
        JobSourceType sourceType,
        String sourceRunId,
        String sourceNodeId,
        UUID parentJobId,
        String operation,
        MediaJobStatus status,
        String idempotencyKey,
        String requestHash,
        InputSnapshot inputSnapshot,
        ModelSnapshot modelSnapshot,
        Long providerId,
        String providerTaskId,
        String submissionKey,
        long version,
        String leaseOwner,
        Instant leaseUntil,
        long leaseEpoch,
        boolean inflightReserved,
        Instant nextActionAt,
        String blockedReason,
        boolean attentionRequired,
        String errorCode,
        String errorSummary,
        Instant cancelRequestedAt,
        Instant observationDeadlineAt,
        Instant reconcileAfter,
        String manualResolution,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt) {

    public boolean terminal() {
        return status.terminal();
    }
}
