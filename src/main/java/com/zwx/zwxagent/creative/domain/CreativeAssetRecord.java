package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.UUID;

/** creative_asset 行记录。 */
public record CreativeAssetRecord(UUID id, UUID projectId, String tenantId, String userId,
                                  UUID sourceJobId, Integer outputIndex, AssetOrigin origin,
                                  String mediaType, StorageBackend storageBackend, String objectKey,
                                  String mimeType, long byteSize, String sha256,
                                  Integer width, Integer height, Long durationMs,
                                  String displayName, boolean starred, AssetStatus status,
                                  String metadataJson, Instant createdAt, Instant deletedAt) {
}
