package com.zwx.aster.eval.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** eval_experiment 行的只读映射；candidate/totals/budget 保持原始 JSON 字符串，由服务层解析。 */
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
                                   int concurrency,
                                   String budgetJson,
                                   UUID resumeOf,
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

    public Map<String, Object> candidate() {
        return EvalJson.parseMap(candidateJson);
    }

    public TargetView target() {
        return TargetView.from(candidate());
    }

    public RunBudget budget() {
        return RunBudget.from(EvalJson.parseMap(budgetJson));
    }

    public int safeConcurrency() {
        return Math.min(Math.max(concurrency, 1), 8);
    }
}
