package com.zwx.aster.eval.service;

import com.zwx.aster.eval.domain.EvalExperimentRecord;
import com.zwx.aster.eval.repository.EvalEventRepository;
import com.zwx.aster.eval.repository.EvalExperimentRepository;
import com.zwx.aster.eval.repository.EvalResultRepository;
import com.zwx.aster.eval.security.JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 实验编排：创建（候选快照）、查询、事件、取消、双实验对比。 */
@Service
public class EvalExperimentService {

    private final EvalExperimentRepository experimentRepository;
    private final EvalDatasetService datasetService;
    private final EvalResultRepository resultRepository;
    private final EvalEventRepository eventRepository;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.dashscope.model:qwen-plus}")
    private String evalModel;

    public EvalExperimentService(EvalExperimentRepository experimentRepository,
                                 EvalDatasetService datasetService,
                                 EvalResultRepository resultRepository,
                                 EvalEventRepository eventRepository,
                                 TransactionTemplate transactionTemplate) {
        this.experimentRepository = experimentRepository;
        this.datasetService = datasetService;
        this.resultRepository = resultRepository;
        this.eventRepository = eventRepository;
        this.transactionTemplate = transactionTemplate;
    }

    public EvalExperimentRecord create(JwtVerifier.Actor actor, UUID datasetId, Integer versionNo,
                                       String notes, String gitCommit) {
        var dataset = datasetService.getDataset(actor.tenantId(), datasetId);
        int version = versionNo == null ? datasetService.latestPublishedVersion(datasetId) : versionNo;
        if (version <= 0) {
            throw new IllegalArgumentException("数据集没有已发布版本，请先发布");
        }
        if (datasetService.publishedCases(actor.tenantId(), datasetId, version).isEmpty()) {
            throw new IllegalArgumentException("数据集版本 v" + version + " 没有用例");
        }
        Map<String, Object> candidate = new LinkedHashMap<>();
        if (notes != null && !notes.isBlank()) candidate.put("notes", notes);
        candidate.put("intentProvider", "backend-internal");
        candidate.put("evalModel", Map.of("provider", "dashscope", "modelName", evalModel,
                "source", "eval-service-config"));
        candidate.put("agentModel", Map.of("provider", "backend-internal", "source", "internal-api"));
        EvalExperimentRecord record = new EvalExperimentRecord(UUID.randomUUID(), actor.tenantId(),
                actor.userId(), datasetId, version, String.valueOf(dataset.get("eval_type")), "queued",
                com.zwx.aster.eval.domain.EvalJson.toJson(candidate), gitCommit,
                null, null, null, 0, null, null, 0, null, null, null, null);
        record = experimentRepository.insert(record);
        eventRepository.append(record.id(), "EXPERIMENT_QUEUED", Map.of(
                "dataset", String.valueOf(dataset.get("name")), "version", version));
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
        get(actor, id);
        return resultRepository.results(id, limit);
    }

    public Map<String, Object> events(JwtVerifier.Actor actor, UUID id, int afterSeq, int limit) {
        get(actor, id);
        List<Map<String, Object>> items = eventRepository.events(id, afterSeq, limit);
        int nextSeq = items.isEmpty() ? afterSeq
                : ((Number) items.get(items.size() - 1).get("seq")).intValue();
        return Map.of("items", items, "nextSeq", nextSeq);
    }

    public Map<String, Object> compare(JwtVerifier.Actor actor, UUID leftId, UUID rightId) {
        var left = view(get(actor, leftId));
        var right = view(get(actor, rightId));
        Map<String, Map<String, Object>> leftByCase = new LinkedHashMap<>();
        for (Map<String, Object> row : resultRepository.results(leftId, 500)) {
            leftByCase.put(String.valueOf(row.get("case_external_id")), row);
        }
        Map<String, Map<String, Object>> rightByCase = new LinkedHashMap<>();
        for (Map<String, Object> row : resultRepository.results(rightId, 500)) {
            rightByCase.put(String.valueOf(row.get("case_external_id")), row);
        }
        List<Map<String, Object>> cases = new java.util.ArrayList<>();
        var allIds = new java.util.TreeSet<String>();
        allIds.addAll(leftByCase.keySet());
        allIds.addAll(rightByCase.keySet());
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
            if (l != null && l.get("latency_ms") instanceof Number ln) item.put("leftLatencyMs", ln.longValue());
            if (r != null && r.get("latency_ms") instanceof Number rn) item.put("rightLatencyMs", rn.longValue());
            cases.add(item);
        }
        return Map.of("left", left, "right", right, "cases", cases);
    }

    public static Map<String, Object> view(EvalExperimentRecord record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", record.id().toString());
        view.put("datasetId", record.datasetId().toString());
        view.put("datasetVersionNo", record.datasetVersionNo());
        view.put("evalType", record.evalType());
        view.put("status", record.status());
        view.put("candidate", com.zwx.aster.eval.domain.EvalJson.parseMap(record.candidateJson()));
        view.put("gitCommit", record.gitCommit());
        view.put("totals", com.zwx.aster.eval.domain.EvalJson.parseMap(record.totalsJson()));
        view.put("errorCode", record.errorCode());
        view.put("errorMessage", record.errorMessage());
        view.put("startedAt", record.startedAt());
        view.put("finishedAt", record.finishedAt());
        view.put("createdAt", record.createdAt());
        return view;
    }
}
