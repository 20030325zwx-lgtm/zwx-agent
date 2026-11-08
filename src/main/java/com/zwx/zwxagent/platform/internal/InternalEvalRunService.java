package com.zwx.zwxagent.platform.internal;

import com.zwx.zwxagent.agent.execution.AgentRun;
import com.zwx.zwxagent.agent.execution.AgentRunService;
import com.zwx.zwxagent.agent.execution.RunTrajectoryService;
import com.zwx.zwxagent.agent.graph.ManusGraphOrchestrator;
import com.zwx.zwxagent.agent.graph.PlanStep;
import com.zwx.zwxagent.capability.mcp.McpServerConfigurationService;
import com.zwx.zwxagent.capability.mcp.McpTools;
import com.zwx.zwxagent.capability.tools.ToolFactory;
import com.zwx.zwxagent.chat.conversation.AgentConversationService;
import com.zwx.zwxagent.chat.conversation.ConversationLockManager;
import com.zwx.zwxagent.knowledge.retrieval.AgentKnowledgeRagResult;
import com.zwx.zwxagent.knowledge.retrieval.AgentKnowledgeRagService;
import com.zwx.zwxagent.platform.intent.IntentDecision;
import com.zwx.zwxagent.platform.intent.IntentRouter;
import com.zwx.zwxagent.platform.settings.DynamicChatModelFactory;
import com.zwx.zwxagent.platform.settings.ModelCatalogService;
import com.zwx.zwxagent.platform.security.AppUser;
import com.zwx.zwxagent.platform.security.Role;
import com.zwx.zwxagent.platform.security.UserRepository;
import com.zwx.zwxagent.platform.workspace.RunWorkspaceService;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 评测中台的 TASK 执行器（主站内侧）：复刻 ManusAgentController.chat 的准备链路，
 * 以隔离评测账号 + YOLO 无人值守跑一次完整任务，同步返回评测证据。
 * 供 eval-service 通过 /api/internal/eval/task-run 调用。
 */
@Service
public class InternalEvalRunService {

    private static final String SUPER_AGENT_KEY = "super";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InternalEvalRunService.class);

    private final AgentConversationService agentConversationService;
    private final ConversationLockManager conversationLockManager;
    private final IntentRouter intentRouter;
    private final AgentKnowledgeRagService agentKnowledgeRagService;
    private final McpServerConfigurationService mcpServerConfigurationService;
    private final ToolFactory toolFactory;
    private final DynamicChatModelFactory dynamicChatModelFactory;
    private final ModelCatalogService modelCatalogService;
    private final AgentRunService agentRunService;
    private final ManusGraphOrchestrator manusGraphOrchestrator;
    private final RunTrajectoryService runTrajectoryService;
    private final com.zwx.zwxagent.platform.workspace.ProjectExecutionLockService projectExecutionLockService;
    private final RunWorkspaceService runWorkspaceService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.intent.short-circuit-enabled:true}")
    private boolean shortCircuitEnabled;
    @Value("${eval.account.username:eval-bot}")
    private String evalUsername;
    @Value("${eval.account.password:eval-bot-local}")
    private String evalPassword;
    @Value("${eval.task.timeout-minutes:15}")
    private long defaultTimeoutMinutes;

    private final ExecutorService evalExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public InternalEvalRunService(AgentConversationService agentConversationService,
                                  ConversationLockManager conversationLockManager,
                                  IntentRouter intentRouter,
                                  AgentKnowledgeRagService agentKnowledgeRagService,
                                  McpServerConfigurationService mcpServerConfigurationService,
                                  ToolFactory toolFactory,
                                  DynamicChatModelFactory dynamicChatModelFactory,
                                  ModelCatalogService modelCatalogService,
                                  AgentRunService agentRunService,
                                  ManusGraphOrchestrator manusGraphOrchestrator,
                                  RunTrajectoryService runTrajectoryService,
                                  com.zwx.zwxagent.platform.workspace.ProjectExecutionLockService projectExecutionLockService,
                                  RunWorkspaceService runWorkspaceService,
                                  UserRepository userRepository,
                                  PasswordEncoder passwordEncoder) {
        this.agentConversationService = agentConversationService;
        this.conversationLockManager = conversationLockManager;
        this.intentRouter = intentRouter;
        this.agentKnowledgeRagService = agentKnowledgeRagService;
        this.mcpServerConfigurationService = mcpServerConfigurationService;
        this.toolFactory = toolFactory;
        this.dynamicChatModelFactory = dynamicChatModelFactory;
        this.modelCatalogService = modelCatalogService;
        this.agentRunService = agentRunService;
        this.manusGraphOrchestrator = manusGraphOrchestrator;
        this.runTrajectoryService = runTrajectoryService;
        this.projectExecutionLockService = projectExecutionLockService;
        this.runWorkspaceService = runWorkspaceService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PreDestroy
    void shutdown() {
        evalExecutor.shutdownNow();
    }

    public Map<String, Object> run(String tenantId, String task, boolean webSearch, boolean knowledgeSearch,
                                   Integer timeoutMinutes) {
        long startedAt = System.currentTimeMillis();
        long timeout = timeoutMinutes == null || timeoutMinutes <= 0
                ? defaultTimeoutMinutes : timeoutMinutes;
        try {
            return execute(tenantId, task, webSearch, knowledgeSearch, timeout, startedAt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failure("任务执行被中断", startedAt);
        } catch (RuntimeException exception) {
            return failure(exception.getMessage(), startedAt);
        }
    }

    private Map<String, Object> execute(String tenantId, String task, boolean webSearch, boolean knowledgeSearch,
                                        long timeoutMinutes, long startedAt) throws InterruptedException {
        if (task == null || task.isBlank()) {
            return failure("task 不能为空", startedAt);
        }
        AppUser evalUser = ensureEvalUser(tenantId);
        String conversationId = UUID.randomUUID().toString();
        AutoCloseable conversationLease = conversationLockManager.tryAcquire(
                evalUser.tenantId() + ":" + evalUser.id() + ":super:" + conversationId);
        if (conversationLease == null) {
            return failure("评测会话锁获取失败", startedAt);
        }
        McpTools mcpTools = null;
        String runId = null;
        try {
            long userMessageId = agentConversationService.startUserTurn(
                    evalUser.tenantId(), evalUser.id(), SUPER_AGENT_KEY, conversationId, task, List.of(), null);
            String prefaceActivity = null;
            var intentDecision = intentRouter.classify(task);
            boolean casualShortCircuit = false;
            if (intentDecision.isPresent()) {
                IntentDecision decision = intentDecision.get();
                var effect = decision.applyTo(knowledgeSearch, webSearch);
                prefaceActivity = toJson(Map.of("phase", "plan", "agent", "意图识别",
                        "summary", decision.summary(effect.action())));
                casualShortCircuit = shortCircuitEnabled
                        && decision.recommend() == IntentDecision.Recommendation.SHORT_CIRCUIT_CANDIDATE
                        && !knowledgeSearch && !webSearch;
            }
            if (casualShortCircuit) {
                return casualDirect(evalUser, conversationId, userMessageId, conversationLease, task);
            }
            AgentKnowledgeRagResult knowledge = knowledgeSearch
                    ? agentKnowledgeRagService.retrieveWithContext(evalUser.tenantId(), SUPER_AGENT_KEY, task)
                    : new AgentKnowledgeRagResult("", List.of(), null, null);
            mcpTools = mcpServerConfigurationService.toolsFor(evalUser.tenantId());
            java.util.List<org.springframework.ai.chat.messages.Message> historyTurns = agentConversationService
                    .getRecentMessages(evalUser.tenantId(), evalUser.id(), SUPER_AGENT_KEY, conversationId, 21).stream()
                    .filter(message -> message.id() != userMessageId)
                    .filter(message -> message.content() != null && !message.content().isBlank())
                    .map(message -> (org.springframework.ai.chat.messages.Message) (
                            "USER".equals(message.role())
                                    ? new org.springframework.ai.chat.messages.UserMessage(message.content())
                                    : new org.springframework.ai.chat.messages.AssistantMessage(message.content())))
                    .toList();
            var modelSelection = resolveForAgent(evalUser.id());
            ChatModel runChatModel = dynamicChatModelFactory.model(modelSelection.config());
            String knowledgeContext = knowledge.context() == null ? "" : knowledge.context();
            String ragTraceJson = knowledge.trace() == null ? "{}" : toJson(knowledge.trace());
            runId = agentRunService.start(conversationId, evalUser.tenantId(), evalUser.id(), SUPER_AGENT_KEY,
                    userMessageId, task, "", knowledgeContext, ragTraceJson, "YOLO");
            final String startedRunId = runId;
            final String finalRagTraceJson = ragTraceJson;
            modelCatalogService.attachToRun(runId, modelSelection);
            ToolCallback[] availableTools = Stream.concat(
                            Stream.of(toolFactory.createTools(evalUser.tenantId(), evalUser.id(), SUPER_AGENT_KEY,
                                    conversationId, runId, webSearch)),
                            java.util.Arrays.stream(mcpTools.callbacks()))
                    .toArray(ToolCallback[]::new);
            final McpTools activeMcpTools = mcpTools;
            Runnable cleanup = () -> {
                try {
                    projectExecutionLockService.release(startedRunId);
                } catch (RuntimeException ignored) {
                    // 评测路径释放失败不影响结果
                }
                activeMcpTools.close();
                closeQuietly(conversationLease);
            };
            AtomicReference<ManusGraphOrchestrator.ManusRunResult> resultRef = new AtomicReference<>();
            List<Boolean> planGated = new ArrayList<>(List.of(false));
            CountDownLatch latch = new CountDownLatch(1);
            ManusGraphOrchestrator.ManusRunRequest request = new ManusGraphOrchestrator.ManusRunRequest(
                    conversationId, evalUser.tenantId(), evalUser.id(), runChatModel, task, historyTurns,
                    availableTools, knowledgeContext, knowledge.trace() == null ? null : finalRagTraceJson,
                    prefaceActivity, startedRunId, "YOLO", null, null, cleanup,
                    result -> {
                        persistTurn(evalUser.tenantId(), evalUser.id(), conversationId, userMessageId,
                                startedRunId, finalRagTraceJson, result);
                        resultRef.set(result);
                        latch.countDown();
                    }, null);
            manusGraphOrchestrator.runStream(request, evalExecutor);
            long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(timeoutMinutes);
            while (!latch.await(500, TimeUnit.MILLISECONDS)) {
                if (System.currentTimeMillis() > deadline) {
                    manusGraphOrchestrator.cancel(runId);
                    latch.await(2, TimeUnit.MINUTES);
                    return failure("任务超过 " + timeoutMinutes + " 分钟时限，已主动停止", startedAt);
                }
                agentRunService.findScoped(evalUser.tenantId(), evalUser.id(), conversationId, runId).ifPresent(run -> {
                    if (run.checkpoint() != null && "plan_pending".equals(run.checkpoint().verificationStatus())) {
                        planGated.set(0, true);
                    }
                });
            }
            ManusGraphOrchestrator.ManusRunResult result = resultRef.get();
            AgentRun run = agentRunService.findScoped(evalUser.tenantId(), evalUser.id(), conversationId, runId)
                    .orElse(null);
            List<Map<String, Object>> plan = planMaps(run);
            String verification = run != null && run.checkpoint() != null
                    ? run.checkpoint().verificationStatus() : null;
            List<String> errors = new ArrayList<>();
            if (result.error() != null && !result.error().isBlank()) errors.add(result.error());
            if (run != null && "FAILED".equals(run.status())) errors.add("run 终态 FAILED");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("answer", result.answer());
            response.put("runStatus", run == null ? "UNKNOWN" : run.status());
            response.put("verification", verification);
            response.put("plan", plan);
            response.put("planProposed", planGated.get(0));
            response.put("errors", errors);
            Map<String, Object> digest = new LinkedHashMap<>();
            digest.put("runId", runId);
            digest.put("conversationId", conversationId);
            digest.put("runStatus", run == null ? "UNKNOWN" : run.status());
            digest.put("verification", verification);
            digest.put("revision", run != null && run.checkpoint() != null ? run.checkpoint().revision() : null);
            digest.put("resumeCount", run == null ? null : run.resumeCount());
            digest.put("planSteps", plan.size());
            if (run != null && run.tokenUsageJson() != null && !run.tokenUsageJson().isBlank()) {
                try {
                    var usage = JSON.readTree(run.tokenUsageJson());
                    digest.put("inputTokens", usage.path("promptTokens").asInt(0));
                    digest.put("outputTokens", usage.path("completionTokens").asInt(0));
                    digest.put("llmCalls", usage.path("calls").asInt(0));
                } catch (Exception ignored) {
                    // token 账目缺失不影响评测结论
                }
            }
            response.put("digest", digest);
            response.put("snapshot", runTrajectoryService
                    .trajectory(evalUser.tenantId(), evalUser.id(), conversationId, runId).orElse(null));
            return response;
        } catch (RuntimeException exception) {
            if (runId != null) {
                try {
                    agentRunService.finish(runId, "FAILED", null, exception.getMessage(), null);
                } catch (RuntimeException ignored) {
                    // 留证失败不改变结论
                }
            }
            return failure(exception.getMessage(), startedAt);
        } finally {
            if (mcpTools != null) mcpTools.close();
            closeQuietly(conversationLease);
        }
    }

    /** 闲聊直达：无 run、无计划，单轮直答并持久化为普通助手回复。 */
    private Map<String, Object> casualDirect(AppUser evalUser, String conversationId, long userMessageId,
                                             AutoCloseable conversationLease, String task) {
        try {
            var modelSelection = resolveForAgent(evalUser.id());
            String answer = ChatClient.builder(dynamicChatModelFactory.model(modelSelection.config())).build()
                    .prompt().user(task).call().content();
            agentConversationService.completeUserTurn(evalUser.tenantId(), evalUser.id(), userMessageId);
            agentConversationService.appendAssistantReply(evalUser.tenantId(), evalUser.id(), SUPER_AGENT_KEY,
                    conversationId, userMessageId, answer == null ? "" : answer, "COMPLETED", null);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("answer", answer);
            response.put("runStatus", null);
            response.put("verification", null);
            response.put("plan", List.of());
            response.put("planProposed", false);
            response.put("errors", List.of());
            response.put("digest", Map.of("path", "casual_short_circuit", "conversationId", conversationId));
            response.put("snapshot", null);
            return response;
        } finally {
            closeQuietly(conversationLease);
        }
    }

    private void persistTurn(String tenantId, String userId, String conversationId, long userMessageId,
                             String runId, String ragTraceJson, ManusGraphOrchestrator.ManusRunResult result) {
        try {
            if (result.completed()) {
                agentConversationService.completeUserTurn(tenantId, userId, userMessageId);
            } else {
                agentConversationService.markUserTurnInterrupted(tenantId, userId, userMessageId);
            }
            Long existingMessageId = agentConversationService.findAssistantReplyId(
                    tenantId, userId, SUPER_AGENT_KEY, conversationId, userMessageId);
            long messageId;
            if (existingMessageId != null) {
                agentConversationService.updateAssistantReply(tenantId, userId, SUPER_AGENT_KEY, conversationId,
                        existingMessageId, result.answer(), result.status(), "[]");
                messageId = existingMessageId;
            } else {
                messageId = agentConversationService.appendAssistantReply(tenantId, userId, SUPER_AGENT_KEY,
                        conversationId, userMessageId, result.answer() == null ? "" : result.answer(),
                        result.status(), runId, ragTraceJson, "[]");
            }
            agentRunService.finish(runId, result.status(), result.answer(), result.error(), messageId);
        } catch (RuntimeException persistenceError) {
            log.error("评测任务收尾持久化失败；conversationId={}, runId={}", conversationId, runId, persistenceError);
        } finally {
            try {
                projectExecutionLockService.release(runId);
            } catch (RuntimeException ignored) {
                // 评测路径释放失败不影响结果
            }
            try {
                runWorkspaceService.finish(runId, result.status());
            } catch (RuntimeException ignored) {
                // 评测用例无工作区
            }
        }
    }

    private AppUser ensureEvalUser(String tenantId) {
        return userRepository.findByUsername(evalUsername)
                .orElseGet(() -> userRepository.insert(tenantId, evalUsername,
                        passwordEncoder.encode(evalPassword), Role.USER));
    }

    private ModelCatalogService.ResolvedSelection resolveForAgent(String userId) {
        try {
            return modelCatalogService.resolveWithFallback(userId,
                    ModelCatalogService.PURPOSE_AGENT, ModelCatalogService.PURPOSE_CHAT);
        } catch (RuntimeException exception) {
            return modelCatalogService.resolve(userId, ModelCatalogService.PURPOSE_CHAT);
        }
    }

    private List<Map<String, Object>> planMaps(AgentRun run) {
        if (run == null || run.checkpoint() == null || run.checkpoint().plan() == null) return List.of();
        List<Map<String, Object>> plan = new ArrayList<>();
        int index = 0;
        for (PlanStep step : run.checkpoint().plan()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", step.id());
            item.put("title", step.title());
            item.put("role", step.role());
            item.put("detail", step.detail());
            item.put("dependsOn", step.dependsOn() == null ? List.of() : step.dependsOn());
            item.put("index", index++);
            plan.add(item);
        }
        return plan;
    }

    private Map<String, Object> failure(String message, long startedAt) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("answer", null);
        response.put("runStatus", "FAILED");
        response.put("plan", List.of());
        response.put("planProposed", false);
        response.put("errors", List.of(String.valueOf(message)));
        response.put("digest", Map.of("elapsedMs", System.currentTimeMillis() - startedAt));
        response.put("error", message);
        return response;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 释放失败不影响评测结论
        }
    }
}
