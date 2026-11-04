package com.zwx.aster.eval.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** eval_case 行的只读映射；payload 已解析为 Map。 */
public record EvalCaseRecord(UUID id,
                             String tenantId,
                             UUID datasetId,
                             int versionNo,
                             String externalId,
                             Map<String, Object> payload,
                             List<String> tags,
                             String severity,
                             String source,
                             Instant createdAt) {

    /** critical 用例漏判会在 totals 单列，避免被总通过率稀释。 */
    public boolean critical() {
        return "critical".equalsIgnoreCase(severity);
    }
}
