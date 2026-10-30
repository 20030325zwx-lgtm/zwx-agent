package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** creative_draft 行记录。originalPrompt 是用户起始想法，effectivePrompt 是实际提交内容（BR06）。 */
public record CreativeDraftRecord(UUID id, UUID projectId, String tenantId, String userId, String operation,
                                  String originalPrompt, String effectivePrompt, List<UUID> referenceAssetIds,
                                  Long modelRowId, Map<String, Object> parameters, long version,
                                  Instant createdAt, Instant updatedAt) {

    public CreativeDraftRecord {
        referenceAssetIds = referenceAssetIds == null ? List.of() : List.copyOf(referenceAssetIds);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
