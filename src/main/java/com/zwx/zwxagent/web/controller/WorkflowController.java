package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.workflow.WorkflowService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工作流试点 API：定义列表、发起运行、运行详情与列表。
 */
@RestController
@RequestMapping("/ai/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }



    @GetMapping
    public Map<String, Object> overview(CurrentActor actor, @RequestParam(defaultValue = "20") int limit) {
        return Map.of("definitions", workflowService.definitions(actor.tenantId()), "runs", workflowService.list(
                actor.tenantId(), actor.userId(), limit));
    }

    @PostMapping("/{workflowKey}/runs")
    public WorkflowService.WorkflowRunView start(CurrentActor actor, @PathVariable String workflowKey,
                                                 @RequestBody WorkflowService.StartRequest request) {
        return workflowService.start(actor.tenantId(), actor.userId(), workflowKey, request);
    }

    @PostMapping
    public WorkflowService.DefinitionVersionView create(CurrentActor actor,
                                                        @RequestBody WorkflowService.CreateWorkflowRequest request) {
        return workflowService.createWorkflow(actor.tenantId(), actor.userId(), request);
    }

    @DeleteMapping("/{workflowKey}")
    public Map<String, Object> delete(CurrentActor actor, @PathVariable String workflowKey) {
        workflowService.deleteWorkflow(actor.tenantId(), workflowKey);
        return Map.of("deleted", true);
    }

    @GetMapping("/{workflowKey}/definition")
    public WorkflowService.DefinitionVersionView definition(CurrentActor actor, @PathVariable String workflowKey) {
        return workflowService.latestVersionView(actor.tenantId(), workflowKey);
    }

    @PostMapping("/{workflowKey}/versions")
    public WorkflowService.DefinitionVersionView saveVersion(CurrentActor actor, @PathVariable String workflowKey,
                                                             @RequestBody WorkflowService.SaveVersionRequest request) {
        return workflowService.saveVersion(actor.tenantId(), actor.userId(), workflowKey, request);
    }

    @GetMapping("/runs")
    public List<WorkflowService.WorkflowRunView> list(CurrentActor actor,
                                                      @RequestParam(required = false) java.util.UUID projectId,
                                                      @RequestParam(defaultValue = "20") int limit) {
        return workflowService.list(actor.tenantId(), actor.userId(), projectId, limit);
    }

    @GetMapping("/runs/{runId}")
    public WorkflowService.WorkflowRunView detail(CurrentActor actor, @PathVariable String runId) {
        return workflowService.view(runId, actor.tenantId(), actor.userId());
    }

    /** C 批：从指定节点重跑（祖先产物复用，不重复计费；平行分支跳过）。 */
    public record RerunRequest(String fromNode) {
    }

    @PostMapping("/runs/{runId}/rerun")
    public WorkflowService.WorkflowRunView rerun(CurrentActor actor, @PathVariable String runId,
                                                 @RequestBody RerunRequest request) {
        return workflowService.rerunFrom(actor.tenantId(), actor.userId(), runId,
                request == null ? "" : request.fromNode());
    }

    /** D 批：导出最新版本为脱敏 bundle（仅定义与输入声明）。 */
    @GetMapping("/{workflowKey}/export")
    public Map<String, Object> export(CurrentActor actor, @PathVariable String workflowKey) {
        return workflowService.exportWorkflow(actor.tenantId(), workflowKey);
    }

    /** D 批：导入 bundle；key 冲突自动改名（响应带最终 key，前端提示用）。 */
    @PostMapping("/import")
    public Map<String, Object> importWorkflow(CurrentActor actor,
                                              @RequestBody Map<String, Object> bundle) {
        WorkflowService.ImportResult result =
                workflowService.importWorkflow(actor.tenantId(), actor.userId(), bundle);
        return Map.of("key", result.key(), "definition", result.definition());
    }
}
