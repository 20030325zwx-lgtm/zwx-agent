package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.creative.service.MediaJobService;
import com.zwx.zwxagent.creative.domain.MediaJobRecord;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 媒体任务 API（C04）：POST /jobs 幂等受理（新建 202 / 复用 200）；
 * GET /jobs/{id} 基础详情（C08 扩展 allowedActions/SSE）。
 */
@RestController
@RequestMapping("/ai/creative/jobs")
public class CreativeJobController {

    private final MediaJobService jobService;

    public CreativeJobController(MediaJobService jobService) {
        this.jobService = jobService;
    }

    public record CreateJobBody(UUID projectId, String operation, Long modelRowId,
                                String originalPrompt, String effectivePrompt,
                                Map<String, Object> parameters, UUID draftId,
                                UUID parentJobId, Boolean acknowledgePossibleDuplicateCharge) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            CurrentActor actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateJobBody body) {
        if (body == null || body.projectId() == null) {
            throw new IllegalArgumentException("缺少 projectId");
        }
        var result = jobService.create(actor, idempotencyKey, body.projectId(),
                new MediaJobService.CreateJobRequest(body.projectId(), body.operation(), body.modelRowId(),
                        body.originalPrompt(), body.effectivePrompt(), body.parameters(),
                        null, body.draftId(), body.parentJobId(),
                        body.acknowledgePossibleDuplicateCharge()));
        return ResponseEntity
                .status(result.reused() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(Map.of("jobId", result.jobId().toString(), "status", result.status(),
                        "reused", result.reused(), "version", result.version()));
    }

    @GetMapping("/{jobId}")
    public Map<String, Object> get(CurrentActor actor, @PathVariable UUID jobId) {
        MediaJobRecord job = jobService.getForOwner(actor, jobId);
        return Map.ofEntries(
                Map.entry("jobId", job.id().toString()),
                Map.entry("projectId", job.projectId().toString()),
                Map.entry("operation", job.operation()),
                Map.entry("status", job.status().name()),
                Map.entry("version", job.version()),
                Map.entry("attentionRequired", job.attentionRequired()),
                Map.entry("blockedReason", job.blockedReason() == null ? "" : job.blockedReason()),
                Map.entry("error", job.errorSummary() == null ? "" : job.errorSummary()),
                Map.entry("providerTaskId", job.providerTaskId() == null ? "" : job.providerTaskId()),
                Map.entry("createdAt", job.createdAt().toString()),
                Map.entry("updatedAt", job.updatedAt().toString()));
    }
}
