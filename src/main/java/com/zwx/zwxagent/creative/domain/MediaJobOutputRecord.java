package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.UUID;

/** media_job_output 行记录：进入 FETCHING 前预分配的产物槽位（实施手册 §2.2）。 */
public record MediaJobOutputRecord(UUID id, UUID jobId, int outputIndex, UUID assetId,
                                   IngestState ingestState, StorageBackend storageBackend, String objectKey,
                                   String sha256, Long byteSize, String lastError,
                                   Instant createdAt, Instant updatedAt) {
}
