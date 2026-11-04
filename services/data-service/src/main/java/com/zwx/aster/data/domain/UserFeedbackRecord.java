package com.zwx.aster.data.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** platform.user_feedback 行的只读映射。 */
public record UserFeedbackRecord(UUID id,
                                 String tenantId,
                                 String userId,
                                 String rating,
                                 String source,
                                 String status,
                                 String reason,
                                 List<String> tags,
                                 Map<String, Object> refs,
                                 UUID convertedDatasetId,
                                 UUID convertedCaseId,
                                 Instant createdAt,
                                 Instant updatedAt) {

    public boolean converted() {
        return "converted".equals(status);
    }
}
