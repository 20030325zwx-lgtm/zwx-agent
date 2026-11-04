package com.zwx.aster.eval.service;

import com.zwx.aster.eval.domain.CaseOutcome;
import com.zwx.aster.eval.domain.EvalCaseRecord;
import com.zwx.aster.eval.domain.EvalExperimentRecord;
import com.zwx.aster.eval.domain.EvalJson;
import com.zwx.aster.eval.domain.RunBudget;
import com.zwx.aster.eval.repository.EvalEventRepository;
import com.zwx.aster.eval.repository.EvalExperimentRepository;
import com.zwx.aster.eval.repository.EvalResultRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单实验执行器：按实验并发度分片执行用例；用例失败不中断实验；用例间检查取消与预算。
 * 结果按 (experiment, external_id) upsert，崩溃或续跑时跳过已完成用例，因此可以断点继续。
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

    @Value("${eval.runner.case-retry:1}")
    private int caseRetry;

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
        ExecutorService pool = null;
        try {
            boolean started = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                if (experimentRepository.lockById(experiment.id()).isEmpty()
                        || !experimentRepository.markRunningGuarded(experiment.id())) return false;
                return true;
            }));
            if (!started) return;

            RunBudget budget = experiment.budget();
            List<EvalCaseRecord> all = datasetService.publishedCases(
                    experiment.tenantId(), experiment.datasetId(), experiment.datasetVersionNo());
            List<EvalCaseRecord> cases = budget.maxCases() > 0 && all.size() > budget.maxCases()
                    ? all.subList(0, budget.maxCases()) : all;
            Set<String> completed = new HashSet<>(resultRepository.completedCaseIds(experiment.id()));
            List<EvalCaseRecord> todo = cases.stream()
                    .filter(caseRow -> !completed.contains(caseRow.externalId()))
                    .toList();

            eventRepository.append(experiment.id(), "EXPERIMENT_STARTED", Map.of(
                    "datasetId", experiment.datasetId().toString(),
                    "datasetVersion", experiment.datasetVersionNo(),
                    "evalType", experiment.evalType(),
                    "target", experiment.target().describe(),
                    "total", cases.size(),
                    "alreadyDone", completed.size(),
                    "pending", todo.size(),
                    "concurrency", experiment.safeConcurrency()));

            AtomicInteger finishedCount = new AtomicInteger();
            AtomicReference<BigDecimal> spent = new AtomicReference<>(BigDecimal.ZERO);
            AtomicBoolean stop = new AtomicBoolean(false);
            AtomicReference<String> stopReason = new AtomicReference<>();

            int concurrency = Math.max(1, Math.min(experiment.safeConcurrency(), Math.max(todo.size(), 1)));
            pool = Executors.newFixedThreadPool(concurrency, runnable -> {
                Thread thread = new Thread(runnable, "eval-case-worker");
                thread.setDaemon(true);
                return thread;
            });
            List<Future<?>> futures = new ArrayList<>();
            for (EvalCaseRecord caseRow : todo) {
                futures.add(pool.submit(() -> {
                    if (stop.get()) return;
                    if (cancelled(experiment.id())) {
                        stop.compareAndSet(false, true);
                        stopReason.compareAndSet(null, "cancelled");
                        return;
                    }
                    extendLease(experiment, token);
                    CaseOutcome outcome = executeWithRetry(experiment, caseRow);
                    try {
                        resultRepository.upsert(experiment.id(), experiment.tenantId(), caseRow, outcome);
                    } catch (RuntimeException exception) {
                        log.warn("评测结果落库失败；experimentId={} case={}；{}", experiment.id(),
                                caseRow.externalId(), exception.getMessage(), exception);
                    }
                    int done = finishedCount.incrementAndGet();
                    eventRepository.append(experiment.id(), "CASE_FINISHED", Map.of(
                            "externalId", caseRow.externalId(),
                            "index", done,
                            "total", todo.size(),
                            "passed", outcome.passed() == null ? "skipped" : outcome.passed()));
                    BigDecimal caseCost = outcome.usage() == null ? null : outcome.usage().cost();
                    if (caseCost != null) {
                        BigDecimal total = spent.accumulateAndGet(caseCost, BigDecimal::add);
                        if (budget.maxCost() != null && total.compareTo(budget.maxCost()) > 0) {
                            stop.compareAndSet(false, true);
                            stopReason.compareAndSet(null, "budget");
                        }
                    }
                }));
            }
            pool.shutdown();
            awaitWithLease(pool, experiment, token, stop);

            if ("cancelled".equals(stopReason.get())) {
                eventRepository.append(experiment.id(), "EXPERIMENT_CANCELLED",
                        Map.of("atCase", finishedCount.get()));
                return;
            }

            Map<String, Object> totals = totals(resultRepository.results(
                    experiment.id(), experiment.datasetId(), experiment.datasetVersionNo(), 500));
            totals.put("spentCost", spent.get());

            if ("budget".equals(stopReason.get())) {
                finish(experiment.id(), "failed", totals, "BUDGET_EXCEEDED",
                        "超出实验预算上限 " + budget.maxCost() + " 元，已停止（完成 "
                                + finishedCount.get() + "/" + todo.size() + " 例）");
                return;
            }
            if (finish(experiment.id(), "succeeded", totals, null, null)) {
                eventRepository.append(experiment.id(), "EXPERIMENT_FINISHED", totals);
            }
        } catch (RuntimeException exception) {
            log.warn("评测实验执行失败；experimentId={}；{}", experiment.id(), exception.getMessage(), exception);
            finish(experiment.id(), "failed", null, "EXECUTION_ERROR", String.valueOf(exception.getMessage()));
        } finally {
            if (pool != null) pool.shutdownNow();
            experimentRepository.releaseLease(experiment.id(), token.leaseEpoch());
        }
    }

    /** 等待执行完成期间持续续租并检查取消，避免长实验被误判过期而重复认领。 */
    private void awaitWithLease(ExecutorService pool, EvalExperimentRecord experiment,
                                EvalExperimentRepository.ClaimToken token, AtomicBoolean stop) {
        try {
            while (!pool.awaitTermination(15, TimeUnit.SECONDS)) {
                extendLease(experiment, token);
                if (cancelled(experiment.id())) {
                    stop.set(true);
                    pool.shutdownNow();
                    pool.awaitTermination(30, TimeUnit.SECONDS);
                    return;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    private CaseOutcome executeWithRetry(EvalExperimentRecord experiment, EvalCaseRecord caseRow) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= Math.max(caseRetry, 0); attempt++) {
            try {
                return targetService.execute(experiment, caseRow);
            } catch (RuntimeException exception) {
                last = exception;
                if (attempt < caseRetry) {
                    log.warn("评测用例执行失败，{}ms 后重试；case={}；{}", 1000L * (attempt + 1),
                            caseRow.externalId(), exception.getMessage());
                    try {
                        Thread.sleep(1000L * (attempt + 1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.warn("评测用例执行失败（已重试）；experimentId={} case={}；{}", experiment.id(),
                caseRow.externalId(), last == null ? "unknown" : last.getMessage());
        return CaseOutcome.failure(last == null ? "执行失败" : last.getMessage(), 0);
    }

    private void extendLease(EvalExperimentRecord experiment, EvalExperimentRepository.ClaimToken token) {
        experimentRepository.extendLeaseFenced(experiment.id(), token.leaseEpoch(),
                Instant.now().plusSeconds(Math.max(leaseSeconds, 60)));
    }

    private boolean cancelled(java.util.UUID experimentId) {
        return experimentRepository.findById(experimentId)
                .map(record -> "cancelled".equals(record.status()))
                .orElse(true);
    }

    private boolean finish(java.util.UUID experimentId, String status, Map<String, Object> totals,
                           String errorCode, String errorMessage) {
        return Boolean.TRUE.equals(transactionTemplate.execute(transactionStatus -> {
            if (experimentRepository.lockById(experimentId).isEmpty()) return false;
            return experimentRepository.finishGuarded(experimentId, status,
                    totals == null ? null : EvalJson.toJson(totals), errorCode, errorMessage);
        }));
    }

    /** 汇总：通过率、延迟、judge 均分、成本与 token、critical 用例。 */
    static Map<String, Object> totals(List<Map<String, Object>> results) {
        int total = results.size();
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        long latencySum = 0;
        int latencyCount = 0;
        double judgeSum = 0;
        int judgeCount = 0;
        long inputTokens = 0;
        int inputTokenCount = 0;
        long outputTokens = 0;
        int outputTokenCount = 0;
        BigDecimal costSum = BigDecimal.ZERO;
        int costCount = 0;
        int criticalTotal = 0;
        int criticalFailed = 0;
        for (Map<String, Object> row : results) {
            Boolean rowPassed = (Boolean) row.get("passed");
            if (rowPassed == null) skipped++;
            else if (rowPassed) passed++;
            else failed++;
            if ("critical".equalsIgnoreCase(String.valueOf(row.get("severity")))) {
                criticalTotal++;
                if (Boolean.FALSE.equals(rowPassed)) criticalFailed++;
            }
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
            if (row.get("input_tokens") instanceof Number n) {
                inputTokens += n.longValue();
                inputTokenCount++;
            }
            if (row.get("output_tokens") instanceof Number n) {
                outputTokens += n.longValue();
                outputTokenCount++;
            }
            if (row.get("cost_amount") instanceof Number n) {
                costSum = costSum.add(BigDecimal.valueOf(n.doubleValue()));
                costCount++;
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
        totals.put("judgeCount", judgeCount);
        totals.put("totalCost", costCount == 0 ? null : costSum.setScale(6, RoundingMode.HALF_UP));
        totals.put("avgCostPerCase", costCount == 0 ? null
                : costSum.divide(BigDecimal.valueOf(costCount), 6, RoundingMode.HALF_UP));
        totals.put("inputTokens", inputTokenCount == 0 ? null : inputTokens);
        totals.put("outputTokens", outputTokenCount == 0 ? null : outputTokens);
        totals.put("criticalTotal", criticalTotal);
        totals.put("criticalFailed", criticalFailed);
        return totals;
    }
}
