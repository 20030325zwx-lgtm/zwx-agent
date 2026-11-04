package com.zwx.aster.eval.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** eval_case 行的只读映射；payload 已解析为 Map。 */
public record EvalCaseRecord(UUID id,
                             String tenantId,
                             UUID datasetId,
                             int versionNo,
                             String externalId,
                             Map<String, Object> payload,
                             String source,
                             Instant createdAt) {
}
