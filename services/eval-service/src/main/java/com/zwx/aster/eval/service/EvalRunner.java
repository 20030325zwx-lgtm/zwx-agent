package com.zwx.aster.eval.service;

import com.zwx.aster.eval.domain.CaseOutcome;
import com.zwx.aster.eval.domain.EvalCaseRecord;
import com.zwx.aster.eval.domain.EvalExperimentRecord;
import com.zwx.aster.eval.domain.EvalJson;
import com.zwx.aster.eval.repository.EvalEventRepository;
import com.zwx.aster.eval.repository.EvalExperimentRepository;
import com.zwx.aster.eval.repository.EvalResultRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单实验执行器：认领后逐用例串行执行；用例失败不中断实验；用例间检查取消与续租。
 * 结果按 (experiment, external_id) upsert，崩溃重跑幂等。
 */
@Component
public class EvalRunner {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EvalRunner.class);

    private final EvalExperimentRepository experimentRepository;
    private final EvalDatasetService datasetService;
    private final EvalResultRepository resultRepository;
    private final EvalEventRepository eventRepository;
    private final EvalTargetService targetService;
    private final TransactionTemplate transactionTemplate;

    @Value("${eval.scheduler.lease-seconds:900}")
    private int leaseSeconds;

    public EvalRunner(EvalExperimentRepository experimentRepository,
                      EvalDatasetService datasetService,
                      EvalResultRepository resultRepository,
                      EvalEventRepository eventRepository,
                      EvalTargetService targetService,
                      TransactionTemplate transactionTemplate) {
        this.experimentRepository = experimentRepository;
        this.datasetService = datasetService;
        this.resultRepository = resultRepository;
        this.eventRepository = eventRepository;
        this.targetService = targetService;
        this.transactionTemplate = transactionTemplate;
    }

    public void run(EvalExperimentRecord experiment, EvalExperimentRepository.ClaimToken token) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (experimentRepository.lockById(experiment.id()).isEmpty()
                        || !experimentRepository.markRunningGuarded(experiment.id())) return;
            });
            eventRepository.append(experiment.id(), "EXPERIMENT_STARTED", Map.of(
                    "datasetId", experiment.datasetId().toString(),
                    "datasetVersion", experiment.datasetVersionNo(),
                    "evalType", experiment.evalType()));
            List<EvalCaseRecord> cases = datasetService.publishedCases(
                    experiment.tenantId(), experiment.datasetId(), experiment.datasetVersionNo());
            int index = 0;
            for (EvalCaseRecord caseRow : cases) {
                index++;
                var current = experimentRepository.findById(experiment.id()).orElse(null);
                if (current == null || "cancelled".equals(current.status())) {
                    eventRepository.append(experiment.id(), "EXPERIMENT_CANCELLED", Map.of("atCase", index));
                    return;
                }
                experimentRepository.extendLeaseFenced(experiment.id(), token.leaseEpoch(),
                        java.time.Instant.now().plusSeconds(Math.max(leaseSeconds, 60)));
                CaseOutcome outcome;
                try {
                    outcome = targetService.execute(experiment, caseRow);
                    resultRepository.upsert(experiment.id(), experiment.tenantId(), caseRow, outcome);
                    eventRepository.append(experiment.id(), "CASE_FINISHED", Map.of(
                            "externalId", caseRow.externalId(),
                            "index", index,
                            "total", cases.size(),
                            "passed", outcome.passed() == null ? "skipped" : outcome.passed()));
                } catch (RuntimeException exception) {
                    log.warn("评测用例执行/留证失败；experimentId={} case={}；{}", experiment.id(),
                            caseRow.externalId(), exception.getMessage(), exception);
                    resultRepository.upsert(experiment.id(), experiment.tenantId(), caseRow,
                            CaseOutcome.failure(exception.getMessage(), 0));
                    eventRepository.append(experiment.id(), "CASE_FAILED", Map.of(
                            "externalId", caseRow.externalId(),
                            "index", index,
                            "error", String.valueOf(exception.getMessage())));
                }
            }
            Map<String, Object> totals = totals(resultRepository.results(experiment.id(), 500));
            boolean finished = transactionTemplate.execute(status -> {
                if (experimentRepository.lockById(experiment.id()).isEmpty()) return false;
                return experimentRepository.finishGuarded(experiment.id(), "succeeded",
                        EvalJson.toJson(totals), null, null);
            });
            if (finished) {
                eventRepository.append(experiment.id(), "EXPERIMENT_FINISHED", totals);
            }
        } catch (RuntimeException exception) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    if (experimentRepository.lockById(experiment.id()).isEmpty()) return;
                    experimentRepository.finishGuarded(experiment.id(), "failed", null,
                            "EXECUTION_ERROR", String.valueOf(exception.getMessage()));
                });
                eventRepository.append(experiment.id(), "EXPERIMENT_FAILED",
                        Map.of("error", String.valueOf(exception.getMessage())));
            } catch (RuntimeException nested) {
                log.warn("评测实验失败状态写回失败；experimentId={}", experiment.id(), nested);
            }
        } finally {
            experimentRepository.releaseLease(experiment.id(), token.leaseEpoch());
        }
    }

    /** 汇总：total/passed/failed/skipped/passRate/avgLatencyMs/judgeAvg。 */
    static Map<String, Object> totals(List<Map<String, Object>> results) {
        int total = results.size();
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        long latencySum = 0;
        int latencyCount = 0;
        double judgeSum = 0;
        int judgeCount = 0;
        for (Map<String, Object> row : results) {
            Boolean rowPassed = (Boolean) row.get("passed");
            if (rowPassed == null) skipped++;
            else if (rowPassed) passed++;
            else failed++;
            if (row.get("latency_ms") instanceof Number n && n.longValue() > 0) {
                latencySum += n.longValue();
                latencyCount++;
            }
            if (row.get("judge") instanceof String judgeJson && !judgeJson.isBlank()) {
                var judge = EvalJson.parseMap(judgeJson);
                if (judge.get("average") instanceof Number n) {
                    judgeSum += n.doubleValue();
                    judgeCount++;
                }
            }
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("total", total);
        totals.put("passed", passed);
        totals.put("failed", failed);
        totals.put("skipped", skipped);
        int scored = passed + failed;
        totals.put("passRate", scored == 0 ? null : Math.round(1000.0 * passed / scored) / 1000.0);
        totals.put("avgLatencyMs", latencyCount == 0 ? null : latencySum / latencyCount);
        totals.put("judgeAvg", judgeCount == 0 ? null : Math.round(100.0 * judgeSum / judgeCount) / 100.0);
        return totals;
    }
}
