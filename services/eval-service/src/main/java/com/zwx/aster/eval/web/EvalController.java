package com.zwx.aster.eval.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.zwx.aster.eval.domain.EvalJson;
import com.zwx.aster.eval.domain.EvalType;
import com.zwx.aster.eval.security.JwtVerifier;
import com.zwx.aster.eval.service.EvalDatasetService;
import com.zwx.aster.eval.service.EvalExperimentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 评测中台 API（独立服务）：数据集/版本/用例 + 实验/结果/事件/对比。管理员权限。 */
@RestController
@RequestMapping("/api/ai/eval")
public class EvalController {

    private final EvalDatasetService datasetService;
    private final EvalExperimentService experimentService;

    public EvalController(EvalDatasetService datasetService, EvalExperimentService experimentService) {
        this.datasetService = datasetService;
        this.experimentService = experimentService;
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

    @DeleteMapping("/datasets/{id}")
    public Map<String, Object> deleteDataset(JwtVerifier.Actor actor, @PathVariable UUID id) {
        actor.requireAdmin();
        return Map.of("deleted", datasetService.deleteDataset(actor.tenantId(), id) > 0);
    }

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
        if (version <= 0) return datasetService.draftCases(actor.tenantId(), id);
        return datasetService.publishedCases(actor.tenantId(), id, version).stream()
                .map(caseRow -> Map.<String, Object>of(
                        "external_id", caseRow.externalId(),
                        "payload", caseRow.payload(),
                        "source", caseRow.source()))
                .toList();
    }

    @PostMapping("/datasets/{id}/publish")
    public Map<String, Object> publish(JwtVerifier.Actor actor, @PathVariable UUID id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        actor.requireAdmin();
        int version = datasetService.publish(actor.tenantId(), id,
                body == null ? null : stringOf(body.get("notes")));
        return Map.of("version", version);
    }

    // ---------------------------------------------------------------- 实验

    @PostMapping("/experiments")
    public Map<String, Object> createExperiment(JwtVerifier.Actor actor, @RequestBody Map<String, Object> body) {
        actor.requireAdmin();
        String datasetId = stringOf(body.get("datasetId"));
        if (datasetId == null || datasetId.isBlank()) throw new IllegalArgumentException("datasetId is required");
        Integer versionNo = body.get("versionNo") instanceof Number n ? n.intValue() : null;
        var record = experimentService.create(actor, UUID.fromString(datasetId), versionNo,
                stringOf(body.get("notes")), stringOf(body.get("gitCommit")));
        return Map.of("id", record.id().toString(), "status", record.status());
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
                                      @RequestParam(defaultValue = "0") int afterSeq,
                                      @RequestParam(defaultValue = "50") int limit) {
        actor.requireAdmin();
        return experimentService.events(actor, id, afterSeq, limit);
    }

    @PostMapping("/experiments/{id}/cancel")
    public Map<String, Object> cancel(JwtVerifier.Actor actor, @PathVariable UUID id) {
        actor.requireAdmin();
        return experimentService.cancel(actor, id);
    }

    @GetMapping("/experiments/compare")
    public Map<String, Object> compare(JwtVerifier.Actor actor, @RequestParam UUID left,
                                       @RequestParam UUID right) {
        actor.requireAdmin();
        return experimentService.compare(actor, left, right);
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
