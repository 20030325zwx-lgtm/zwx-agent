package com.zwx.aster.eval.service;

import com.zwx.aster.eval.domain.EvalExperimentRecord;
import com.zwx.aster.eval.domain.EvalJson;
import com.zwx.aster.eval.domain.RunBudget;
import com.zwx.aster.eval.domain.TargetSpec;
import com.zwx.aster.eval.domain.TargetView;
import com.zwx.aster.eval.repository.EvalEventRepository;
import com.zwx.aster.eval.repository.EvalExperimentRepository;
import com.zwx.aster.eval.repository.EvalResultRepository;
import com.zwx.aster.eval.security.JwtVerifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 实验编排：创建（被测对象快照）、续跑、查询、事件、取消、双实验对比。 */
@Service
public class EvalExperimentService {

    private final EvalExperimentRepository experimentRepository;
    private final EvalDatasetService datasetService;
    private final EvalResultRepository resultRepository;
    private final EvalEventRepository eventRepository;
    private final TransactionTemplate transactionTemplate;
    private final TargetResolver targetResolver;

    public EvalExperimentService(EvalExperimentRepository experimentRepository,
                                 EvalDatasetService datasetService,
                                 EvalResultRepository resultRepository,
                                 EvalEventRepository eventRepository,
                                 TransactionTemplate transactionTemplate,
                                 TargetResolver targetResolver) {
        this.experimentRepository = experimentRepository;
        this.datasetService = datasetService;
        this.resultRepository = resultRepository;
        this.eventRepository = eventRepository;
        this.transactionTemplate = transactionTemplate;
        this.targetResolver = targetResolver;
    }

    public EvalExperimentRecord create(JwtVerifier.Actor actor, UUID datasetId, Integer versionNo,
                                       String notes, String gitCommit, TargetSpec targetSpec,
                                       int concurrency, RunBudget budget) {
        var dataset = datasetService.getDataset(actor.tenantId(), datasetId);
        int version = versionNo == null ? datasetService.latestPublishedVersion(datasetId) : versionNo;
        if (version <= 0) {
            throw new IllegalArgumentException("数据集没有已发布版本，请先发布");
        }
        if (datasetService.publishedCases(actor.tenantId(), datasetId, version).isEmpty()) {
            throw new IllegalArgumentException("数据集版本 v" + version + " 没有用例");
        }
        var evalType = com.zwx.aster.eval.domain.EvalType.parse(String.valueOf(dataset.get("eval_type")));
        TargetSpec spec = targetSpec == null ? TargetSpec.live() : targetSpec;
        spec.validate(evalType);
        Map<String, Object> candidate = targetResolver.resolve(actor.tenantId(), spec, evalType, notes);

        EvalExperimentRecord record = new EvalExperimentRecord(UUID.randomUUID(), actor.tenantId(),
                actor.userId(), datasetId, version, evalType.lower(), "queued",
                EvalJson.toJson(candidate), gitCommit,
                null, null, null, 0, null, null, 0,
                Math.min(Math.max(concurrency, 1), 8),
                EvalJson.toJson(budget == null ? new RunBudget(0, null, 1.0).toMap() : budget.toMap()),
                null,
                null, null, null, null);
        record = experimentRepository.insert(record);
        eventRepository.append(record.id(), "EXPERIMENT_QUEUED", Map.of(
                "dataset", String.valueOf(dataset.get("name")),
                "version", version,
                "target", TargetView.from(candidate).describe()));
        return record;
    }

    /** 续跑：复用同一数据集版本与同一被测对象快照，跳过来源实验已完成的用例。 */
    public EvalExperimentRecord resume(JwtVerifier.Actor actor, UUID experimentId) {
        EvalExperimentRecord source = get(actor, experimentId);
        if (source.active()) {
            throw new IllegalArgumentException("实验仍在执行，不能续跑");
        }
        EvalExperimentRecord record = new EvalExperimentRecord(UUID.randomUUID(), source.tenantId(),
                actor.userId(), source.datasetId(), source.datasetVersionNo(), source.evalType(), "queued",
                source.candidateJson(), source.gitCommit(),
                null, null, null, 0, null, null, 0,
                source.concurrency(), source.budgetJson(), source.id(),
                null, null, null, null);
        record = experimentRepository.insert(record);
        resultRepository.copyResultsFrom(source.id(), record.id(), actor.tenantId());
        eventRepository.append(record.id(), "EXPERIMENT_QUEUED", Map.of(
                "resumeOf", source.id().toString(),
                "target", source.target().describe()));
        return record;
    }

    public Map<String, Object> list(JwtVerifier.Actor actor, Long cursorMillis, String cursorId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<EvalExperimentRecord> items = experimentRepository.listRecent(
                actor.tenantId(), cursorMillis, cursorId, safeLimit);
        String nextCursor = null;
        if (items.size() == safeLimit) {
            var last = items.get(items.size() - 1);
            nextCursor = last.createdAt().toEpochMilli() + ":" + last.id();
        }
        return Map.of("items", items.stream().map(EvalExperimentService::view).toList(),
                "nextCursor", nextCursor);
    }

    public EvalExperimentRecord get(JwtVerifier.Actor actor, UUID id) {
        EvalExperimentRecord record = experimentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("实验不存在：" + id));
        if (!record.tenantId().equals(actor.tenantId())) {
            throw new IllegalArgumentException("实验不存在：" + id);
        }
        return record;
    }

    public Map<String, Object> cancel(JwtVerifier.Actor actor, UUID id) {
        get(actor, id);
        boolean cancelled = transactionTemplate.execute(status -> {
            if (experimentRepository.lockById(id).isEmpty()) return false;
            return experimentRepository.finishGuarded(id, "cancelled", null, null, null);
        });
        if (cancelled) eventRepository.append(id, "EXPERIMENT_CANCELLED", Map.of("by", actor.userId()));
        return Map.of("cancelled", cancelled);
    }

    public List<Map<String, Object>> results(JwtVerifier.Actor actor, UUID id, int limit) {
        EvalExperimentRecord record = get(actor, id);
        return resultRepository.results(id, record.datasetId(), record.datasetVersionNo(), limit);
    }

    public Map<String, Object> events(JwtVerifier.Actor actor, UUID id, long afterSeq, int limit) {
        get(actor, id);
        List<Map<String, Object>> items = eventRepository.events(id, afterSeq, limit);
        long nextSeq = items.isEmpty() ? afterSeq
                : ((Number) items.get(items.size() - 1).get("seq")).longValue();
        return Map.of("items", items, "nextSeq", nextSeq);
    }

    public Map<String, Object> compare(JwtVerifier.Actor actor, UUID leftId, UUID rightId) {
        EvalExperimentRecord leftRecord = get(actor, leftId);
        EvalExperimentRecord rightRecord = get(actor, rightId);
        var left = view(leftRecord);
        var right = view(rightRecord);
        Map<String, Map<String, Object>> leftByCase = new LinkedHashMap<>();
        for (Map<String, Object> row : resultRepository.results(leftId,
                leftRecord.datasetId(), leftRecord.datasetVersionNo(), 500)) {
            leftByCase.put(String.valueOf(row.get("case_external_id")), row);
        }
        Map<String, Map<String, Object>> rightByCase = new LinkedHashMap<>();
        for (Map<String, Object> row : resultRepository.results(rightId,
                rightRecord.datasetId(), rightRecord.datasetVersionNo(), 500)) {
            rightByCase.put(String.valueOf(row.get("case_external_id")), row);
        }
        List<Map<String, Object>> cases = new java.util.ArrayList<>();
        var allIds = new java.util.TreeSet<String>();
        allIds.addAll(leftByCase.keySet());
        allIds.addAll(rightByCase.keySet());
        int leftOnly = 0;
        int rightOnly = 0;
        for (String caseId : allIds) {
            var l = leftByCase.get(caseId);
            var r = rightByCase.get(caseId);
            Boolean lp = l == null ? null : (Boolean) l.get("passed");
            Boolean rp = r == null ? null : (Boolean) r.get("passed");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("externalId", caseId);
            item.put("leftPassed", lp);
            item.put("rightPassed", rp);
            item.put("delta", lp == null || rp == null ? null
                    : (lp.equals(rp) ? "same" : lp ? "left_only" : "right_only"));
            if ("left_only".equals(item.get("delta"))) leftOnly++;
            if ("right_only".equals(item.get("delta"))) rightOnly++;
            if (l != null && l.get("latency_ms") instanceof Number ln) item.put("leftLatencyMs", ln.longValue());
            if (r != null && r.get("latency_ms") instanceof Number rn) item.put("rightLatencyMs", rn.longValue());
            if (l != null && l.get("cost_amount") != null) item.put("leftCost", l.get("cost_amount"));
            if (r != null && r.get("cost_amount") != null) item.put("rightCost", r.get("cost_amount"));
            cases.add(item);
        }
        return Map.of("left", left, "right", right, "cases", cases,
                "summary", Map.of("leftOnly", leftOnly, "rightOnly", rightOnly,
                        "same", allIds.size() - leftOnly - rightOnly));
    }

    public static Map<String, Object> view(EvalExperimentRecord record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", record.id().toString());
        view.put("datasetId", record.datasetId().toString());
        view.put("datasetVersionNo", record.datasetVersionNo());
        view.put("evalType", record.evalType());
        view.put("status", record.status());
        view.put("candidate", EvalJson.parseMap(record.candidateJson()));
        view.put("target", record.target().describe());
        view.put("concurrency", record.concurrency());
        view.put("budget", EvalJson.parseMap(record.budgetJson()));
        view.put("resumeOf", record.resumeOf() == null ? null : record.resumeOf().toString());
        view.put("gitCommit", record.gitCommit());
        view.put("totals", EvalJson.parseMap(record.totalsJson()));
        view.put("errorCode", record.errorCode());
        view.put("errorMessage", record.errorMessage());
        view.put("startedAt", record.startedAt());
        view.put("finishedAt", record.finishedAt());
        view.put("createdAt", record.createdAt());
        return view;
    }
}
