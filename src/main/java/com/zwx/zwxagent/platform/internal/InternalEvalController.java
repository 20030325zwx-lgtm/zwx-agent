package com.zwx.zwxagent.platform.internal;

import com.zwx.zwxagent.knowledge.retrieval.AgentKnowledgeRagService;
import com.zwx.zwxagent.platform.intent.IntentDecision;
import com.zwx.zwxagent.platform.intent.IntentRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务间内部 API（X-Aster-Internal-Token）：供评测中台独立服务调用。
 * 只暴露「评测执行需要」的最小能力：意图分类、检索校验、TASK 无头执行。
 */
@RestController
@RequestMapping("/internal/eval")
public class InternalEvalController {

    private final IntentRouter intentRouter;
    private final AgentKnowledgeRagService agentKnowledgeRagService;
    private final InternalEvalRunService runService;

    @Value("${app.intent.provider:none}")
    private String intentProvider;

    public InternalEvalController(IntentRouter intentRouter,
                                  AgentKnowledgeRagService agentKnowledgeRagService,
                                  InternalEvalRunService runService) {
        this.intentRouter = intentRouter;
        this.agentKnowledgeRagService = agentKnowledgeRagService;
        this.runService = runService;
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
}
