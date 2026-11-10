package com.zwx.zwxagent.platform.internal;

import com.zwx.zwxagent.agent.execution.AgentRunService;
import com.zwx.zwxagent.agent.execution.RunTrajectoryService;
import com.zwx.zwxagent.knowledge.retrieval.AgentKnowledgeRagService;
import com.zwx.zwxagent.platform.intent.IntentDecision;
import com.zwx.zwxagent.platform.intent.IntentRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务间内部 API（X-Aster-Internal-Token）：供评测中台/数据中台调用。
 * 只暴露「评测与回流需要」的最小能力：意图分类、检索校验、TASK 无头执行、轨迹只读。
 */
@RestController
@RequestMapping("/internal/eval")
public class InternalEvalController {

    private final IntentRouter intentRouter;
    private final AgentKnowledgeRagService agentKnowledgeRagService;
    private final InternalEvalRunService runService;
    private final AgentRunService agentRunService;
    private final RunTrajectoryService runTrajectoryService;

    @Value("${app.intent.provider:none}")
    private String intentProvider;

    public InternalEvalController(IntentRouter intentRouter,
                                  AgentKnowledgeRagService agentKnowledgeRagService,
                                  InternalEvalRunService runService,
                                  AgentRunService agentRunService,
                                  RunTrajectoryService runTrajectoryService) {
        this.intentRouter = intentRouter;
        this.agentKnowledgeRagService = agentKnowledgeRagService;
        this.runService = runService;
        this.agentRunService = agentRunService;
        this.runTrajectoryService = runTrajectoryService;
    }

    public record IntentRequest(String question) {
    }

    @PostMapping("/intent")
    public Map<String, Object> intent(@RequestBody IntentRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();
        if ("none".equalsIgnoreCase(intentProvider)) {
            response.put("available", false);
            response.put("router", "none");
            return response;
        }
        var decision = intentRouter.classify(request.question());
        if (decision.isEmpty()) {
            response.put("available", false);
            response.put("router", intentProvider);
            return response;
        }
        IntentDecision d = decision.get();
        response.put("available", true);
        response.put("router", intentProvider);
        response.put("intent", d.intent());
        response.put("confidence", d.intentConfidence());
        response.put("complexity", d.complexity());
        response.put("latencyMs", d.latencyMs());
        return response;
    }

    public record RetrievalRequest(String tenantId, String agentKey, String documentId, String query) {
    }

    @PostMapping("/verify-retrieval")
    public Map<String, Object> verifyRetrieval(@RequestBody RetrievalRequest request) {
        if (request.tenantId() == null || request.agentKey() == null || request.documentId() == null) {
            throw new IllegalArgumentException("tenantId/agentKey/documentId 均为必填");
        }
        List<org.springframework.ai.document.Document> matches = agentKnowledgeRagService.verifyDocument(
                request.tenantId(), request.agentKey(), request.documentId(), request.query());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("matched", !matches.isEmpty());
        response.put("matchedCount", matches.size());
        if (!matches.isEmpty() && matches.get(0).getScore() != null) {
            response.put("topScore", matches.get(0).getScore());
        }
        return response;
    }

    public record RetrievalEvalRequest(String tenantId, String agentKey, String query) {
    }

    /** 离线评测入口：返回完整检索 trace（块级候选/各通道排名/分段耗时），供 golden set runner 算 recall@k/MRR。 */
    @PostMapping("/retrieval")
    public Map<String, Object> retrievalEval(@RequestBody RetrievalEvalRequest request) {
        if (request.tenantId() == null || request.agentKey() == null
                || request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("tenantId/agentKey/query 均为必填");
        }
        var result = agentKnowledgeRagService.retrieveWithContext(request.tenantId(), request.agentKey(), request.query());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", String.valueOf(result.outcome().status()));
        response.put("context", result.context());
        response.put("trace", result.trace());
        return response;
    }

    public record TaskRunRequest(String tenantId, String task, Boolean webSearch, Boolean knowledge,
                                 Integer timeoutMinutes) {
    }

    @PostMapping("/task-run")
    public Map<String, Object> taskRun(@RequestBody TaskRunRequest request) {
        if (request.tenantId() == null || request.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return runService.run(request.tenantId(), request.task(),
                Boolean.TRUE.equals(request.webSearch()),
                Boolean.TRUE.equals(request.knowledge()),
                request.timeoutMinutes());
    }

    // ---------------------------------------------------------------- 轨迹只读（数据中台）

    /** 租户内最近 run：给数据中台「从 trace 建用例」提供入口。 */
    @GetMapping("/runs")
    public List<AgentRunService.TenantRunOverview> runs(@RequestParam String tenantId,
                                                        @RequestParam(defaultValue = "30") int limit) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return agentRunService.findTenantRunOverviews(tenantId, limit);
    }

    /** 按 runId 取聚合轨迹（计划/工具/模型调用/事件），只按租户校验。 */
    @GetMapping("/runs/{runId}/trajectory")
    public Map<String, Object> trajectory(@PathVariable String runId, @RequestParam String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return runTrajectoryService.trajectoryInTenant(tenantId, runId)
                .orElseThrow(() -> new IllegalArgumentException("run 不存在：" + runId));
    }
}
