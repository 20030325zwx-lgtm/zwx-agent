package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.creative.service.CreativeDraftService;
import com.zwx.zwxagent.creative.service.CreativeProjectService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * AI 创作模块 API（C03）：/api/ai/creative 前缀。
 * 归属全部由 CurrentActor 派生；项目/草稿更新与归档走乐观锁（409）。
 */
@RestController
@RequestMapping("/ai/creative")
public class CreativeProjectController {

    private final CreativeProjectService projectService;
    private final CreativeDraftService draftService;
    private final com.zwx.zwxagent.creative.service.CreativeCanvasService canvasService;

    public CreativeProjectController(CreativeProjectService projectService, CreativeDraftService draftService,
                                     com.zwx.zwxagent.creative.service.CreativeCanvasService canvasService) {
        this.projectService = projectService;
        this.draftService = draftService;
        this.canvasService = canvasService;
    }

    public record CreateProjectBody(String name, String description, String kind) {
    }

    public record UpdateProjectBody(String name, String description, String status, Long expectedVersion) {
    }

    public record CreateDraftBody(String operation, String originalPrompt, String effectivePrompt) {
    }

    public record UpdateDraftBody(String operation, String originalPrompt, String effectivePrompt,
                                  Long expectedVersion) {
    }

    @PostMapping("/projects")
    public CreativeProjectService.ProjectView create(CurrentActor actor,
                                                     @RequestBody CreateProjectBody body) {
        return projectService.create(actor, new CreativeProjectService.CreateProjectRequest(
                body == null ? null : body.name(), body == null ? null : body.description(),
                body == null ? null : body.kind()));
    }

    @GetMapping("/projects")
    public CreativeProjectService.ProjectList list(CurrentActor actor,
                                                   @RequestParam(required = false) String kind,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(required = false) String sort,
                                                   @RequestParam(required = false) String cursor,
                                                   @RequestParam(required = false) Integer limit) {
        return projectService.list(actor, kind, q, sort, cursor, limit);
    }

    @GetMapping("/projects/{projectId}")
    public CreativeProjectService.ProjectView get(CurrentActor actor, @PathVariable UUID projectId) {
        return projectService.get(actor, projectId);
    }

    public record SaveCanvasBody(JsonNode graph, JsonNode viewport, Long expectedRevision) {}
    public record CanvasRunBody(String scope, String nodeId, Long expectedRevision, String idempotencyKey) {}
    public record CanvasSelectionBody(String nodeId, UUID assetId) {}

    @GetMapping("/projects/{projectId}/canvas")
    public com.zwx.zwxagent.creative.service.CreativeCanvasService.CanvasView getCanvas(
            CurrentActor actor, @PathVariable UUID projectId) {
        return canvasService.get(actor, projectId);
    }

    @PutMapping("/projects/{projectId}/canvas")
    public com.zwx.zwxagent.creative.service.CreativeCanvasService.CanvasView saveCanvas(
            CurrentActor actor, @PathVariable UUID projectId, @RequestBody SaveCanvasBody body) {
        return canvasService.save(actor, projectId, body == null ? null
                : new com.zwx.zwxagent.creative.service.CreativeCanvasService.SaveRequest(
                        body.graph(), body.viewport(), body.expectedRevision()));
    }

    @PostMapping("/projects/{projectId}/canvas/validate")
    public com.zwx.zwxagent.creative.service.CreativeCanvasService.ValidationView validateCanvasRun(
            CurrentActor actor, @PathVariable UUID projectId, @RequestBody CanvasRunBody body) {
        return canvasService.validateRun(actor, projectId, new com.zwx.zwxagent.creative.service.CreativeCanvasService.RunRequest(
                body == null ? null : body.scope(), body == null ? null : body.nodeId(),
                body == null ? null : body.expectedRevision(), body == null ? null : body.idempotencyKey()));
    }

    @PostMapping("/projects/{projectId}/canvas/runs")
    public com.zwx.zwxagent.creative.service.CreativeCanvasService.CanvasRunView startCanvasRun(
            CurrentActor actor, @PathVariable UUID projectId, @RequestBody CanvasRunBody body) {
        return canvasService.startRun(actor, projectId, new com.zwx.zwxagent.creative.service.CreativeCanvasService.RunRequest(
                body == null ? null : body.scope(), body == null ? null : body.nodeId(),
                body == null ? null : body.expectedRevision(), body == null ? null : body.idempotencyKey()));
    }

    @GetMapping("/projects/{projectId}/canvas/runs")
    public List<com.zwx.zwxagent.creative.service.CreativeCanvasService.CanvasRunView> listCanvasRuns(
            CurrentActor actor, @PathVariable UUID projectId,
            @RequestParam(required = false) Integer limit) {
        return canvasService.listRuns(actor, projectId, limit);
    }

    @GetMapping("/projects/{projectId}/canvas/runs/{runId}")
    public com.zwx.zwxagent.creative.service.CreativeCanvasService.CanvasRunView getCanvasRun(
            CurrentActor actor, @PathVariable UUID projectId, @PathVariable UUID runId) {
        return canvasService.getRun(actor, projectId, runId);
    }

    @PostMapping("/projects/{projectId}/canvas/runs/{runId}/selections")
    public com.zwx.zwxagent.creative.service.CreativeCanvasService.CanvasRunView selectCanvasCandidate(
            CurrentActor actor, @PathVariable UUID projectId, @PathVariable UUID runId,
            @RequestBody CanvasSelectionBody body) {
        return canvasService.selectCandidate(actor, projectId, runId,
                body == null ? null : body.nodeId(), body == null ? null : body.assetId());
    }

    @PostMapping("/projects/{projectId}/canvas/runs/{runId}/cancel")
    public com.zwx.zwxagent.creative.service.CreativeCanvasService.CanvasRunView cancelCanvasRun(
            CurrentActor actor, @PathVariable UUID projectId, @PathVariable UUID runId) {
        return canvasService.cancelRun(actor, projectId, runId);
    }

    @PatchMapping("/projects/{projectId}")
    public CreativeProjectService.ProjectView update(CurrentActor actor, @PathVariable UUID projectId,
                                                     @RequestBody UpdateProjectBody body) {
        return projectService.update(actor, projectId, new CreativeProjectService.UpdateProjectRequest(
                body == null ? null : body.name(),
                body == null ? null : body.description(),
                body == null ? null : body.status(),
                body == null ? null : body.expectedVersion()));
    }

    @PostMapping("/projects/{projectId}/drafts")
    public CreativeDraftService.DraftView createDraft(CurrentActor actor, @PathVariable UUID projectId,
                                                      @RequestBody(required = false) CreateDraftBody body) {
        return draftService.create(actor, projectId, new CreativeDraftService.CreateDraftRequest(
                body == null ? null : body.operation(),
                body == null ? null : body.originalPrompt(),
                body == null ? null : body.effectivePrompt()));
    }

    @GetMapping("/projects/{projectId}/drafts")
    public java.util.List<CreativeDraftService.DraftView> listDrafts(CurrentActor actor,
                                                                     @PathVariable UUID projectId,
                                                                     @RequestParam(defaultValue = "20") int limit) {
        return draftService.list(actor, projectId, limit);
    }

    @GetMapping("/drafts/{draftId}")
    public CreativeDraftService.DraftView getDraft(CurrentActor actor, @PathVariable UUID draftId) {
        return draftService.get(actor, draftId);
    }

    @PatchMapping("/drafts/{draftId}")
    public CreativeDraftService.DraftView updateDraft(CurrentActor actor, @PathVariable UUID draftId,
                                                      @RequestBody UpdateDraftBody body) {
        return draftService.update(actor, draftId, new CreativeDraftService.UpdateDraftRequest(
                body == null ? null : body.operation(),
                body == null ? null : body.originalPrompt(),
                body == null ? null : body.effectivePrompt(),
                body == null ? null : body.expectedVersion()));
    }
}
