package com.zwx.aster.eval.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.zwx.aster.eval.domain.EvalJson;
import com.zwx.aster.eval.domain.EvalType;
import com.zwx.aster.eval.domain.RunBudget;
import com.zwx.aster.eval.domain.TargetSpec;
import com.zwx.aster.eval.security.JwtVerifier;
import com.zwx.aster.eval.service.EvalDatasetService;
import com.zwx.aster.eval.service.EvalExperimentService;
import com.zwx.aster.eval.service.EvalReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 评测中台 API：数据集 / 用例 / 实验 / 报告。管理员权限。 */
@RestController
@RequestMapping("/api/ai/eval")
public class EvalController {

    private final EvalDatasetService datasetService;
    private final EvalExperimentService experimentService;
    private final EvalReportService reportService;

    public EvalController(EvalDatasetService datasetService,
                          EvalExperimentService experimentService,
                          EvalReportService reportService) {
        this.datasetService = datasetService;
        this.experimentService = experimentService;
        this.reportService = reportService;
    }

    // ---------------------------------------------------------------- 数据集

    @PostMapping("/datasets")
    public Map<String, Object> createDataset(JwtVerifier.Actor actor, @RequestBody Map<String, Object> body) {
        actor.requireAdmin();
        String name = stringOf(body.get("name"));
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        UUID id = datasetService.createDataset(actor.tenantId(), name.trim(),
                EvalType.parse(stringOf(body.get("evalType"))), stringOf(body.get("description")));
        return Map.of("id", id.toString());
    }

    @GetMapping("/datasets")
    public List<Map<String, Object>> listDatasets(JwtVerifier.Actor actor) {
        actor.requireAdmin();
        return datasetService.listDatasets(actor.tenantId());
    }

    @GetMapping("/datasets/{id}")
    public Map<String, Object> getDataset(JwtVerifier.Actor actor, @PathVariable UUID id) {
        actor.requireAdmin();
        return datasetService.getDataset(actor.tenantId(), id);
    }

    @PutMapping("/datasets/{id}")
    public Map<String, Object> updateDataset(JwtVerifier.Actor actor, @PathVariable UUID id,
                                             @RequestBody Map<String, Object> body) {
        actor.requireAdmin();
        datasetService.updateDataset(actor.tenantId(), id, stringOf(body.get("name")),
                stringOf(body.get("description")));
        return Map.of("updated", true);
    }

    @DeleteMapping("/datasets/{id}")
    public Map<String, Object> deleteDataset(JwtVerifier.Actor actor, @PathVariable UUID id) {
        actor.requireAdmin();
        return Map.of("deleted", datasetService.deleteDataset(actor.tenantId(), id) > 0);
    }

    // ---------------------------------------------------------------- 用例

    /** 导入用例到草稿：body 为 {cases:[...]} 或数组。 */
    @PostMapping("/datasets/{id}/cases/import")
    public Map<String, Object> importCases(JwtVerifier.Actor actor, @PathVariable UUID id,
                                           @RequestBody JsonNode body) {
        actor.requireAdmin();
        JsonNode casesNode = body.isArray() ? body : body.get("cases");
        if (casesNode == null || !casesNode.isArray() || casesNode.isEmpty()) {
            throw new IllegalArgumentException("body 需要 cases 数组");
        }
        List<Map<String, Object>> cases = new ArrayList<>();
        casesNode.forEach(caseNode -> cases.add(EvalJson.parseMap(caseNode.toString())));
        return datasetService.importCases(actor.tenantId(), id, cases);
    }

    @GetMapping("/datasets/{id}/cases")
    public List<Map<String, Object>> listCases(JwtVerifier.Actor actor, @PathVariable UUID id,
                                               @RequestParam(defaultValue = "0") int version) {
        actor.requireAdmin();
        datasetService.getDataset(actor.tenantId(), id);
        if (version <= 0) return datasetService.draftCases(actor.tenantId(), id);
        return datasetService.publishedCases(actor.tenantId(), id, version).stream()
                .map(caseRow -> {
                    Map<String, Object> item = new java.util.LinkedHashMap<String, Object>();
                    item.put("external_id", caseRow.externalId());
                    item.put("payload", caseRow.payload());
                    item.put("tags", caseRow.tags());
                    item.put("severity", caseRow.severity());
                    item.put("source", caseRow.source());
                    return item;
                })
                .toList();
    }

    @PostMapping("/datasets/{id}/cases")
    public Map<String, Object> addCase(JwtVerifier.Actor actor, @PathVariable UUID id,
                                       @RequestBody Map<String, Object> body) {
        actor.requireAdmin();
        return datasetService.addCase(actor.tenantId(), id, body);
    }

    @PutMapping("/datasets/{id}/cases/{externalId}")
    public Map<String, Object> updateCase(JwtVerifier.Actor actor, @PathVariable UUID id,
                                          @PathVariable String externalId, @RequestBody Map<String, Object> body) {
        actor.requireAdmin();
        datasetService.updateCase(actor.tenantId(), id, externalId, body);
        return Map.of("updated", true);
    }

    @DeleteMapping("/datasets/{id}/cases/{externalId}")
    public Map<String, Object> deleteCase(JwtVerifier.Actor actor, @PathVariable UUID id,
                                          @PathVariable String externalId) {
        actor.requireAdmin();
        datasetService.deleteCase(actor.tenantId(), id, externalId);
        return Map.of("deleted", true);
    }

    @PostMapping("/datasets/{id}/publish")
    public Map<String, Object> publish(JwtVerifier.Actor actor, @PathVariable UUID id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        actor.requireAdmin();
        int version = datasetService.publish(actor.tenantId(), id,
                body == null ? null : stringOf(body.get("notes")));
        return Map.of("version", version);
    }

    /** 从历史版本 fork 回草稿（改坏了的版本可以回退）。 */
    @PostMapping("/datasets/{id}/versions/{version}/fork")
    public Map<String, Object> forkVersion(JwtVerifier.Actor actor, @PathVariable UUID id,
                                           @PathVariable int version,
                                           @RequestParam(defaultValue = "false") boolean confirm) {
        actor.requireAdmin();
        return datasetService.forkVersion(actor.tenantId(), id, version, confirm);
    }

    @GetMapping("/datasets/{id}/trend")
    public Map<String, Object> trend(JwtVerifier.Actor actor, @PathVariable UUID id,
                                     @RequestParam(defaultValue = "20") int limit) {
        actor.requireAdmin();
        datasetService.getDataset(actor.tenantId(), id);
        return reportService.trend(actor.tenantId(), id, limit);
    }

    // ---------------------------------------------------------------- 实验

    @PostMapping("/experiments")
    public Map<String, Object> createExperiment(JwtVerifier.Actor actor, @RequestBody Map<String, Object> body) {
        actor.requireAdmin();
        String datasetId = stringOf(body.get("datasetId"));
        if (datasetId == null || datasetId.isBlank()) throw new IllegalArgumentException("datasetId is required");
        Integer versionNo = body.get("versionNo") instanceof Number n ? n.intValue() : null;
        TargetSpec target = TargetSpec.from(mapOf(body.get("target")));
        int concurrency = body.get("concurrency") instanceof Number n ? n.intValue() : 1;
        RunBudget budget = RunBudget.from(mapOf(body.get("budget")));
        var record = experimentService.create(actor, UUID.fromString(datasetId), versionNo,
                stringOf(body.get("notes")), stringOf(body.get("gitCommit")), target, concurrency, budget);
        return Map.of("id", record.id().toString(),
                "status", record.status(),
                "target", record.target().describe());
    }

    @GetMapping("/experiments")
    public Map<String, Object> listExperiments(JwtVerifier.Actor actor,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(defaultValue = "20") int limit) {
        actor.requireAdmin();
        Long cursorMillis = null;
        String cursorId = null;
        if (cursor != null && cursor.contains(":")) {
            String[] parts = cursor.split(":", 2);
            try {
                cursorMillis = Long.parseLong(parts[0]);
                cursorId = parts[1];
            } catch (NumberFormatException ignored) {
                throw new IllegalArgumentException("cursor 格式非法");
            }
        }
        return experimentService.list(actor, cursorMillis, cursorId, limit);
    }

    @GetMapping("/experiments/{id}")
    public Map<String, Object> getExperiment(JwtVerifier.Actor actor, @PathVariable UUID id) {
        actor.requireAdmin();
        return EvalExperimentService.view(experimentService.get(actor, id));
    }

    @GetMapping("/experiments/{id}/results")
    public List<Map<String, Object>> results(JwtVerifier.Actor actor, @PathVariable UUID id,
                                             @RequestParam(defaultValue = "200") int limit) {
        actor.requireAdmin();
        return experimentService.results(actor, id, limit);
    }

    @GetMapping("/experiments/{id}/events")
    public Map<String, Object> events(JwtVerifier.Actor actor, @PathVariable UUID id,
                                      @RequestParam(defaultValue = "0") long afterSeq,
                                      @RequestParam(defaultValue = "50") int limit) {
        actor.requireAdmin();
        return experimentService.events(actor, id, afterSeq, limit);
    }

    @PostMapping("/experiments/{id}/cancel")
    public Map<String, Object> cancel(JwtVerifier.Actor actor, @PathVariable UUID id) {
        actor.requireAdmin();
        return experimentService.cancel(actor, id);
    }

    /** 续跑：跳过来源实验已完成的用例，只补跑缺的部分。 */
    @PostMapping("/experiments/{id}/resume")
    public Map<String, Object> resume(JwtVerifier.Actor actor, @PathVariable UUID id) {
        actor.requireAdmin();
        var record = experimentService.resume(actor, id);
        return Map.of("id", record.id().toString(), "status", record.status());
    }

    @GetMapping("/experiments/compare")
    public Map<String, Object> compare(JwtVerifier.Actor actor, @RequestParam UUID left,
                                       @RequestParam UUID right) {
        actor.requireAdmin();
        return experimentService.compare(actor, left, right);
    }

    // ---------------------------------------------------------------- 报告

    @GetMapping("/experiments/{id}/report")
    public Map<String, Object> report(JwtVerifier.Actor actor, @PathVariable UUID id,
                                      @RequestParam(defaultValue = "tag") String groupBy) {
        actor.requireAdmin();
        return reportService.report(experimentService.get(actor, id), groupBy);
    }

    /** 门禁：CI/发布脚本可直接消费 {pass, reasons}。 */
    @GetMapping("/experiments/{id}/gate")
    public Map<String, Object> gate(JwtVerifier.Actor actor, @PathVariable UUID id,
                                    @RequestParam(required = false) Double minPassRate,
                                    @RequestParam(required = false) Integer maxCriticalFailed,
                                    @RequestParam(required = false) BigDecimal maxAvgCost) {
        actor.requireAdmin();
        return reportService.gate(experimentService.get(actor, id), minPassRate, maxCriticalFailed, maxAvgCost);
    }

    @GetMapping(value = "/experiments/{id}/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> export(JwtVerifier.Actor actor, @PathVariable UUID id) {
        actor.requireAdmin();
        String csv = reportService.exportCsv(experimentService.get(actor, id));
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"eval-" + id + ".csv\"")
                .body(csv);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
