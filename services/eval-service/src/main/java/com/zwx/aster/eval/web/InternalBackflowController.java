package com.zwx.aster.eval.web;

import com.zwx.aster.eval.service.EvalDatasetService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务间内部接口（X-Aster-Internal-Token）：data-service 把反馈回流为评测用例。
 * 默认写入「线上回流-单轮直调」数据集草稿。
 */
@RestController
@RequestMapping("/api/internal/eval")
public class InternalBackflowController {

    private final EvalDatasetService datasetService;

    public InternalBackflowController(EvalDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    public record BackflowRequest(String tenantId, String feedbackId, String rating,
                                  String reason, String question, String answer) {
    }

    @PostMapping("/backflow")
    public Map<String, Object> backflow(@RequestBody BackflowRequest request) {
        if (request.tenantId() == null || request.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (request.feedbackId() == null || request.feedbackId().length() < 8) {
            throw new IllegalArgumentException("feedbackId is required");
        }
        if (request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("反馈缺少来源问题快照，无法回流");
        }
        UUID datasetId = datasetService.findOrCreateBackflowDataset(request.tenantId());
        Map<String, Object> rawCase = EvalDatasetService.buildBackflowCase(
                request.feedbackId(), request.rating(), request.reason(),
                request.question(), request.answer());
        datasetService.importCases(request.tenantId(), datasetId, List.of(rawCase));
        String externalId = (String) rawCase.get("id");
        UUID caseId = datasetService.findDraftCaseId(datasetId, externalId).orElse(null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("datasetId", datasetId.toString());
        response.put("caseExternalId", externalId);
        response.put("caseId", caseId == null ? null : caseId.toString());
        return response;
    }
}
