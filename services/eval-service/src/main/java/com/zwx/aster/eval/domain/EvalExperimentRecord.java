package com.zwx.aster.eval.domain;

import java.time.Instant;
import java.util.UUID;

/** eval_experiment 行的只读映射；candidate/totals 保持原始 JSON 字符串，由服务层解析。 */
public record EvalExperimentRecord(UUID id,
                                   String tenantId,
                                   String createdBy,
                                   UUID datasetId,
                                   int datasetVersionNo,
                                   String evalType,
                                   String status,
                                   String candidateJson,
                                   String gitCommit,
                                   String totalsJson,
                                   String errorCode,
                                   String errorMessage,
                                   long version,
                                   String leaseOwner,
                                   Instant leaseUntil,
                                   long leaseEpoch,
                                   Instant startedAt,
                                   Instant finishedAt,
                                   Instant createdAt,
                                   Instant updatedAt) {

    public EvalType type() {
        return EvalType.parse(evalType);
    }

    public boolean active() {
        return EvalExperimentStatus.ACTIVE.contains(status);
    }
}
