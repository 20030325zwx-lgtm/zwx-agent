package com.zwx.evaluation.flowable;

import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/poc/document-workflows")
public class DocumentWorkflowController {
    private final RuntimeService runtimeService;
    private final TaskService taskService;

    public DocumentWorkflowController(RuntimeService runtimeService, TaskService taskService) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
    }

    @PostMapping
    public Map<String, String> start(@RequestBody StartRequest request) {
        var process = runtimeService.startProcessInstanceByKey("document-indexing", Map.of(
                "documentId", request.documentId(),
                "tenantId", request.tenantId()
        ));
        return Map.of("processInstanceId", process.getId(), "status", "RUNNING");
    }

    @GetMapping("/{processInstanceId}/tasks")
    public List<TaskView> tasks(@PathVariable String processInstanceId) {
        return taskService.createTaskQuery().processInstanceId(processInstanceId).list().stream()
                .map(task -> new TaskView(task.getId(), task.getName(), task.getAssignee()))
                .toList();
    }

    @PostMapping("/tasks/{taskId}/complete")
    public Map<String, String> complete(@PathVariable String taskId, @RequestBody CompleteRequest request) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
        taskService.complete(taskId, Map.of("approved", request.approved(), "reviewNote", request.reviewNote()));
        return Map.of("processInstanceId", task.getProcessInstanceId(), "status", "CONTINUED");
    }

    public record StartRequest(String documentId, String tenantId) {}
    public record CompleteRequest(boolean approved, String reviewNote) {}
    public record TaskView(String id, String name, String assignee) {}
}
