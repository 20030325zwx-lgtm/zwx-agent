package com.zwx.zwxagent.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.agent.execution.AgentRunSummary;
import com.zwx.zwxagent.agent.execution.AgentRunView;
import com.zwx.zwxagent.agent.execution.AgentRunService;
import com.zwx.zwxagent.agent.execution.AgentToolCallLedgerService;
import com.zwx.zwxagent.agent.execution.RunQuotaService;
import com.zwx.zwxagent.agent.execution.ToolConfirmationService;
import com.zwx.zwxagent.agent.execution.WebSourceService;
import com.zwx.zwxagent.agent.graph.ManusGraphOrchestrator;
import com.zwx.zwxagent.capability.mcp.McpTools;
import com.zwx.zwxagent.capability.tools.ToolFactory;
import com.zwx.zwxagent.capability.tools.ToolSandbox;
import com.zwx.zwxagent.chat.conversation.AgentConversationMessage;
import com.zwx.zwxagent.chat.conversation.AgentConversationService;
import com.zwx.zwxagent.chat.conversation.AgentConversationSummary;
import com.zwx.zwxagent.chat.conversation.ConversationLockManager;
import com.zwx.zwxagent.knowledge.image.ImageAnalysisService;
import com.zwx.zwxagent.knowledge.retrieval.AgentKnowledgeRagResult;
import com.zwx.zwxagent.knowledge.retrieval.AgentKnowledgeRagService;
import com.zwx.zwxagent.knowledge.storage.AgentImageStorageService;
import com.zwx.zwxagent.platform.intent.IntentDecision;
import com.zwx.zwxagent.platform.intent.IntentRouter;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.settings.AppConfigurationService;
import com.zwx.zwxagent.platform.settings.DynamicChatModelFactory;
import com.zwx.zwxagent.platform.workspace.WorkspaceService;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 超级智能体接口：SSE 对话与 run 生命周期（确认/恢复/取消/审计/联网引用）、会话与消息、
 * 图片、工作区文件。原 AiController manus 组拆分，路径保持不变。
 */
@RestController
@RequestMapping("/ai")
public class ManusAgentController {

    private static final String SUPER_AGENT_KEY = "super";
    private static final String SUPER_DEFAULT_TITLE = "新的超级智能体对话";
    private static final Pattern GENERATED_FILE = Pattern.compile("(?:PDF generated successfully to:|File written successfully to:|Resource downloaded successfully to:)\\s*([^\"\\r\\n]+)");

    private static final java.util.concurrent.ScheduledExecutorService HEARTBEAT_SCHEDULER =
            java.util.concurrent.Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    @Resource
    private ToolFactory toolFactory;

    @Resource
    private ToolSandbox toolSandbox;

    @Resource
    private WorkspaceService workspaceService;

    @Resource
    private AppConfigurationService appConfigurationService;

    @Resource
    private DynamicChatModelFactory dynamicChatModelFactory;

    @Resource
    private AgentImageStorageService agentImageStorageService;

    @Resource
    private ImageAnalysisService imageAnalysisService;

    @Resource
    private AgentConversationService agentConversationService;

    @Resource
    private com.zwx.zwxagent.capability.mcp.McpServerConfigurationService mcpServerConfigurationService;

    @Resource
    private AgentKnowledgeRagService agentKnowledgeRagService;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private IntentRouter intentRouter;

    @Resource
    private ConversationLockManager conversationLockManager;

    @Resource(name = "agentExecutor")
    private java.util.concurrent.Executor agentExecutor;

    @Resource
    private ManusGraphOrchestrator manusGraphOrchestrator;

    @Resource
    private AgentRunService agentRunService;

    @Resource
    private AgentToolCallLedgerService agentToolCallLedgerService;

    @Resource
    private ToolConfirmationService toolConfirmationService;

    @Resource
    private com.zwx.zwxagent.agent.observability.AgentMetrics agentMetrics;

    @Resource
    private RunQuotaService runQuotaService;

    @Resource
    private WebSourceService webSourceService;

    /** 批 2 闲聊短路开关：命中候选且用户未勾选开关时单轮直达。provider 关闭时无意图决策，本开关不生效。 */
    @Value("${app.intent.short-circuit-enabled:true}")
    private boolean shortCircuitEnabled;

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize SSE event", exception);
        }
    }

    private Path toolFactoryScope(String conversationId) {
        return toolSandbox.scopeDir(conversationId);
    }

    private List<Map<String, String>> manusAttachments(List<String> activities, Path scopeRoot) {
        Map<String, Map<String, String>> attachments = new LinkedHashMap<>();
        for (String activity : activities) {
            Matcher matcher = GENERATED_FILE.matcher(activity);
            if (!matcher.find()) continue;
            Path file = scopeRoot.resolve(matcher.group(1).trim()).toAbsolutePath().normalize();
            if (!file.startsWith(scopeRoot) || !Files.isRegularFile(file)) continue;
            String relativePath = scopeRoot.relativize(file).toString();
            attachments.putIfAbsent(relativePath, Map.of(
                    "name", file.getFileName().toString(),
                    "path", relativePath,
                    "type", relativePath.toLowerCase().endsWith(".pdf") ? "pdf" : "file"));
        }
        return new ArrayList<>(attachments.values());
    }

    @PostMapping(value = "/manus/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public com.zwx.zwxagent.knowledge.storage.AgentImageUpload uploadManusImage(CurrentActor actor,
                                                                      @RequestParam String conversationId,
                                                                      @RequestPart("file") MultipartFile file) {
        if (!agentConversationService.hasConversation(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Conversation not found");
        }
        return agentImageStorageService.upload(SUPER_AGENT_KEY, conversationId, file);
    }

    @GetMapping("/manus/images")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> getManusImage(
            CurrentActor actor, @RequestParam String conversationId, @RequestParam String objectKey) {
        if (!agentConversationService.hasConversation(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId)) {
            return ResponseEntity.notFound().build();
        }
        var response = agentImageStorageService.getImage(SUPER_AGENT_KEY, conversationId, objectKey);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(response.contentType()))
                .body(new org.springframework.core.io.InputStreamResource(response.inputStream()));
    }

    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(CurrentActor actor, @RequestParam String conversationId, @RequestParam String message,
                                      @RequestParam(defaultValue = "false") boolean knowledgeSearch,
                                      @RequestParam(defaultValue = "true") boolean webSearch,
                                      @RequestParam(required = false) List<String> imageObjectKey,
                                      @RequestParam(required = false) String clientRequestId) {
        agentConversationService.ensureConversation(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId, SUPER_DEFAULT_TITLE, message);
        // 每用户配额：并发 run / 小时频率 / 24h token 预算，超限 429（在会话锁之前，快速拒绝）
        runQuotaService.checkRunAllowed(actor.userId());
        var conversationLease = conversationLockManager.tryAcquire(actor.tenantId() + ":" + actor.userId() + ":super:" + conversationId);
        if (conversationLease == null) throw new com.zwx.zwxagent.chat.conversation.ConversationBusyException();
        long userMessageId;
        List<String> imageObjectKeys = imageObjectKey == null ? List.of() : imageObjectKey.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
        try {
            for (String key : imageObjectKeys) {
                agentImageStorageService.validateObjectKey(SUPER_AGENT_KEY, conversationId, key);
            }
            // 先落 USER 轮次：即使进程随后崩溃，下一轮历史里仍能看到这次提问和图片。
            userMessageId = agentConversationService.startUserTurn(
                    actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId, message, imageObjectKeys, clientRequestId);
        } catch (RuntimeException exception) {
            conversationLease.close();
            throw exception;
        }
        // 意图分流（批 1 生效）：分类前移到检索/工具装配之前，recommend() 只把开关打开、不关闭用户显式勾选；
        // 覆盖未勾选开关时在活动事件中明示。禁用/无 Key/失败 → 维持原开关，行为与未引入该功能一致。
        boolean effectiveKnowledgeSearch = knowledgeSearch;
        boolean effectiveWebSearch = webSearch;
        String prefaceActivity = null;
        var intentDecision = intentRouter.classify(message);
        if (intentDecision.isPresent()) {
            IntentDecision decision = intentDecision.get();
            var effect = decision.applyTo(effectiveKnowledgeSearch, effectiveWebSearch);
            effectiveKnowledgeSearch = effect.knowledgeSearch();
            effectiveWebSearch = effect.webSearch();
            if (agentMetrics != null) agentMetrics.intentDecision(decision.recommend().name().toLowerCase());
            // 图片消息与文本同规则：知识类意图按图片分析改写检索词检索；随便问则不检索，
            // 由模型基于图片分析与自身知识直接作答（worker 知识约束已分级，未覆盖时不会草草结束）。
            prefaceActivity = toJson(java.util.Map.of("phase", "plan", "agent", "意图识别",
                    "summary", decision.summary(effect.action())));
            // 批 2 闲聊短路：高置信纯闲聊 + 用户未勾选任何开关 → 单轮直达回答，跳过 RAG/联网/图编排。
            // 门禁：意图评测集 12/12（2026-10-26 r2）达标后启用；开关关闭或用户显式勾选时不短路。
            if (shortCircuitEnabled && decision.recommend() == IntentDecision.Recommendation.SHORT_CIRCUIT_CANDIDATE
                    && !knowledgeSearch && !webSearch && imageObjectKeys.isEmpty()) {
                runQuotaService.checkRunAllowed(actor.userId());
                String summary = "闲聊直达：命中闲聊短路（置信 " + String.format("%.2f", decision.intentConfidence())
                        + "），本轮跳过知识库检索、联网搜索与多智能体编排";
                return casualChat(actor, conversationId, message, userMessageId, conversationLease,
                        toJson(java.util.Map.of("phase", "plan", "agent", "意图识别", "summary", summary)));
            }
        }
        AgentKnowledgeRagResult knowledge;
        McpTools mcpTools = null;
        ToolCallback[] availableTools;
        String historyContext;
        ChatModel runChatModel;
        String imageContext = "";
        String retrievalQuery = message;
        try {
            if (!imageObjectKeys.isEmpty()) {
                StringBuilder contexts = new StringBuilder();
                int imageIndex = 0;
                for (String key : imageObjectKeys) {
                    imageIndex++;
                    try {
                        String imageUrl = agentImageStorageService.presignedReadUrl(SUPER_AGENT_KEY, conversationId, key);
                        var analysis = imageAnalysisService.analyze(actor.tenantId(), imageUrl, message);
                        if (analysis.available()) {
                            contexts.append("[图片 ").append(imageIndex).append(" 分析]\n")
                                    .append(analysis.contextBlock()).append("\n\n");
                            if (retrievalQuery.equals(message)) retrievalQuery = analysis.queryOr(message);
                        } else {
                            contexts.append("[图片 ").append(imageIndex).append(" 分析失败：")
                                    .append(analysis.error()).append("]\n");
                        }
                    } catch (RuntimeException exception) {
                        contexts.append("[图片 ").append(imageIndex).append(" 分析失败：")
                                .append(exception.getMessage()).append("]\n");
                    }
                }
                imageContext = contexts.toString();
            }
            knowledge = effectiveKnowledgeSearch
                    ? agentKnowledgeRagService.retrieveWithContext(actor.tenantId(), SUPER_AGENT_KEY, retrievalQuery)
                    : new AgentKnowledgeRagResult("", List.of());
            mcpTools = mcpServerConfigurationService.toolsFor(actor.tenantId());
            availableTools = Stream.concat(Arrays.stream(toolFactory.createTools(actor.tenantId(), SUPER_AGENT_KEY, conversationId, effectiveWebSearch)), Arrays.stream(mcpTools.callbacks()))
                    .toArray(ToolCallback[]::new);
            historyContext = agentConversationService
                    .getRecentMessages(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId, 21).stream()
                    .filter(historyMessage -> historyMessage.id() != userMessageId)
                    .map(historyMessage -> {
                        String role = "USER".equals(historyMessage.role()) ? "用户" : "助手";
                        String status = "INTERRUPTED".equals(historyMessage.status()) ? "（上一轮未完成，仅保留部分内容）" : "";
                        return role + status + ": " + historyMessage.content();
                    })
                    .collect(java.util.stream.Collectors.joining("\n"));
            runChatModel = chatModelFor(actor.userId());
        } catch (RuntimeException exception) {
            agentConversationService.markUserTurnInterrupted(actor.tenantId(), actor.userId(), userMessageId);
            if (mcpTools != null) mcpTools.close();
            conversationLease.close();
            throw exception;
        }
        McpTools activeMcpTools = mcpTools;
        String knowledgeContext = imageContext + knowledge.context();
        String agentMessage = imageContext.isBlank() ? message : message + "\n\n" + imageContext;
        String ragTraceJson = knowledge.trace() == null ? "{}" : toJson(knowledge.trace());
        String runId;
        try {
            runId = agentRunService.start(conversationId, actor.tenantId(), actor.userId(), SUPER_AGENT_KEY,
                    userMessageId, agentMessage, historyContext, knowledgeContext, ragTraceJson);
        } catch (RuntimeException exception) {
            agentConversationService.markUserTurnInterrupted(actor.tenantId(), actor.userId(), userMessageId);
            activeMcpTools.close();
            conversationLease.close();
            throw exception;
        }
        Runnable cleanup = () -> {
            activeMcpTools.close();
            conversationLease.close();
        };
        ManusGraphOrchestrator.ManusRunRequest runRequest = new ManusGraphOrchestrator.ManusRunRequest(
                conversationId, actor.tenantId(), runChatModel, agentMessage, historyContext, availableTools, knowledgeContext,
                knowledge.trace() == null ? null : ragTraceJson, prefaceActivity, runId, null, null, cleanup,
                result -> completeManusRun(actor, conversationId, userMessageId, runId, ragTraceJson, null, result));
        ManusGraphOrchestrator.ManusRunHandle handle;
        try {
            handle = manusGraphOrchestrator.runStream(runRequest, agentExecutor);
        } catch (RuntimeException exception) {
            agentConversationService.markUserTurnInterrupted(actor.tenantId(), actor.userId(), userMessageId);
            agentRunService.finish(runId, "FAILED", null, exception.getMessage(), null);
            cleanup.run();
            throw exception;
        }
        manageManusEmitter(handle.emitter(), handle.stream());
        return handle.emitter();
    }

    /** 跨会话最近任务（首页任务流）：深链直达 /super-agent?conversationId=…&runId=…。 */
    @GetMapping("/manus/runs/recent")
    public List<AgentRunSummary> listRecentManusRuns(CurrentActor actor, @RequestParam(defaultValue = "8") int limit) {
        return agentRunService.findRecentRuns(actor.tenantId(), actor.userId(), Math.min(Math.max(limit, 1), 30))
                .stream().map(AgentRunSummary::from).toList();
    }

    @GetMapping("/manus/conversations/{conversationId}/runs")
    public List<AgentRunSummary> listManusRuns(CurrentActor actor, @PathVariable String conversationId,
                                               @RequestParam(defaultValue = "30") int limit) {
        return agentRunService.findScopedRuns(actor.tenantId(), actor.userId(), conversationId,
                        Math.min(Math.max(limit, 1), 100)).stream()
                .map(AgentRunSummary::from)
                .toList();
    }

    @GetMapping("/manus/conversations/{conversationId}/runs/{runId}")
    public AgentRunView getManusRun(CurrentActor actor, @PathVariable String conversationId, @PathVariable String runId) {
        return agentRunService.findScoped(actor.tenantId(), actor.userId(), conversationId, runId)
                .map(AgentRunView::from)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Run not found"));
    }

    @GetMapping("/manus/conversations/{conversationId}/runs/{runId}/reconnect")
    public SseEmitter reconnectManusRun(CurrentActor actor, @PathVariable String conversationId,
                                        @PathVariable String runId,
                                        @RequestParam(required = false) String lastEventId) {
        agentRunService.findScoped(actor.tenantId(), actor.userId(), conversationId, runId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Run not found"));
        ManusGraphOrchestrator.ReconnectResult result = manusGraphOrchestrator.reconnect(runId, lastEventId);
        if (result.emitter() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "history-gap".equals(result.error())
                            ? "SSE history is no longer available; continue from the persisted checkpoint"
                            : "This run is not live on this instance");
        }
        if (!result.completed()) {
            manageManusEmitter(result.emitter(), result.stream());
        }
        return result.emitter();
    }

    @PostMapping("/manus/conversations/{conversationId}/runs/{runId}/cancel")
    public Map<String, Object> cancelManusRun(CurrentActor actor, @PathVariable String conversationId,
                                              @PathVariable String runId) {
        agentRunService.findScoped(actor.tenantId(), actor.userId(), conversationId, runId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Run not found"));
        return Map.of("cancelled", manusGraphOrchestrator.cancel(runId));
    }

    /** run 的工具调用审计清单：来自幂等账本（工具、参数摘要、执行状态），供运行检查器展示。 */
    @GetMapping("/manus/conversations/{conversationId}/runs/{runId}/tool-calls")
    public List<Map<String, Object>> listManusToolCalls(CurrentActor actor, @PathVariable String conversationId,
                                                        @PathVariable String runId,
                                                        @RequestParam(defaultValue = "200") int limit) {
        agentRunService.findScoped(actor.tenantId(), actor.userId(), conversationId, runId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Run not found"));
        return agentToolCallLedgerService.auditForRun(runId, Math.min(Math.max(limit, 1), 500));
    }

    /** run 的联网引用：从幂等账本提取搜索结果与抓取页面的 URL/标题/时间，供前端参考链接展示。 */
    @GetMapping("/manus/conversations/{conversationId}/runs/{runId}/web-sources")
    public List<WebSourceService.WebSource> listManusWebSources(
            CurrentActor actor, @PathVariable String conversationId, @PathVariable String runId) {
        agentRunService.findScoped(actor.tenantId(), actor.userId(), conversationId, runId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Run not found"));
        return webSourceService.sourcesForRun(runId);
    }

    /** 用户对挂起 run 的待确认工具做决定；决定后前端调用既有 resume 接口继续执行。 */
    @PostMapping("/manus/conversations/{conversationId}/runs/{runId}/confirmation")
    public Map<String, Object> decideManusConfirmation(CurrentActor actor, @PathVariable String conversationId,
                                                       @PathVariable String runId,
                                                       @RequestBody ConfirmationDecisionRequest request) {
        com.zwx.zwxagent.agent.execution.AgentRun run = agentRunService
                .findScoped(actor.tenantId(), actor.userId(), conversationId, runId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Run not found"));
        if (!run.awaitingConfirmation()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "This run is not awaiting tool confirmation");
        }
        String decision = request.decision() == null ? "" : request.decision().trim().toUpperCase();
        if (!"APPROVED".equals(decision) && !"DENIED".equals(decision)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "decision must be APPROVED or DENIED");
        }
        String toolName = request.toolName() == null || request.toolName().isBlank() ? null : request.toolName().trim();
        int updated = toolConfirmationService.decide(runId, toolName, decision);
        return Map.of("updated", updated, "decision", decision);
    }

    public record ConfirmationDecisionRequest(String toolName, String decision) {
    }

    @GetMapping("/manus/conversations/{conversationId}/runs/{runId}/resume")
    public SseEmitter resumeManusRun(CurrentActor actor, @PathVariable String conversationId, @PathVariable String runId,
                                     @RequestParam(defaultValue = "true") boolean webSearch) {
        com.zwx.zwxagent.agent.execution.AgentRun run = agentRunService
                .findScoped(actor.tenantId(), actor.userId(), conversationId, runId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Run not found"));
        if (!run.resumable()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "This run has already finished");
        }
        // 恢复同样受并发与频率约束：续跑会继续消耗模型与工具成本
        runQuotaService.checkRunAllowed(actor.userId());
        var conversationLease = conversationLockManager.tryAcquire(
                actor.tenantId() + ":" + actor.userId() + ":super:" + conversationId);
        if (conversationLease == null) throw new com.zwx.zwxagent.chat.conversation.ConversationBusyException();
        McpTools mcpTools = null;
        ToolCallback[] availableTools;
        ChatModel runChatModel;
        try {
            mcpTools = mcpServerConfigurationService.toolsFor(actor.tenantId());
            availableTools = Stream.concat(
                            Arrays.stream(toolFactory.createTools(actor.tenantId(), SUPER_AGENT_KEY, conversationId, webSearch)),
                            Arrays.stream(mcpTools.callbacks()))
                    .toArray(ToolCallback[]::new);
            runChatModel = chatModelFor(actor.userId());
        } catch (RuntimeException exception) {
            if (mcpTools != null) mcpTools.close();
            conversationLease.close();
            throw exception;
        }
        McpTools activeMcpTools = mcpTools;
        com.zwx.zwxagent.agent.graph.GraphCheckpoint checkpoint = run.checkpoint() == null
                ? new com.zwx.zwxagent.agent.graph.GraphCheckpoint(
                        null, null, List.of(), List.of(), List.of(), List.of(), 0, null, null, null, null)
                : run.checkpoint();
        String resumeFrom = com.zwx.zwxagent.agent.graph.ManusGraphOrchestrator.resumeNode(checkpoint);
        org.slf4j.LoggerFactory.getLogger(ManusAgentController.class).debug(
                "resume manus run; runId={}, lastNode={}, resumeFrom={}, planSize={}, resultSize={}",
                runId, checkpoint.lastNode(), resumeFrom, checkpoint.plan().size(), checkpoint.taskResults().size());
        com.zwx.zwxagent.agent.graph.GraphCheckpoint resumeCheckpoint =
                com.zwx.zwxagent.agent.graph.ManusGraphOrchestrator.prepareResumeCheckpoint(checkpoint, resumeFrom);
        if (resumeCheckpoint == null) resumeCheckpoint = checkpoint;
        agentRunService.restart(runId);
        String ragTraceJson = run.ragTraceJson() == null ? "{}" : run.ragTraceJson();
        Runnable cleanup = () -> {
            activeMcpTools.close();
            conversationLease.close();
        };
        com.zwx.zwxagent.agent.graph.ManusGraphOrchestrator.ManusRunRequest runRequest =
                new com.zwx.zwxagent.agent.graph.ManusGraphOrchestrator.ManusRunRequest(
                        conversationId, actor.tenantId(), runChatModel, run.input(),
                        run.historyContext() == null ? "" : run.historyContext(),
                        availableTools, run.knowledgeContext() == null ? "" : run.knowledgeContext(),
                        run.ragTraceJson(), null, runId, resumeCheckpoint, resumeFrom, cleanup,
                        result -> completeManusRun(actor, conversationId, run.userMessageId(), runId,
                                ragTraceJson, run.assistantMessageId(), result));
        com.zwx.zwxagent.agent.graph.ManusGraphOrchestrator.ManusRunHandle handle;
        try {
            handle = manusGraphOrchestrator.runStream(runRequest, agentExecutor);
        } catch (RuntimeException exception) {
            agentRunService.finish(runId, "FAILED", run.displayAnswer(), exception.getMessage(), run.assistantMessageId());
            cleanup.run();
            throw exception;
        }
        manageManusEmitter(handle.emitter(), handle.stream());
        return handle.emitter();
    }

    private ChatModel chatModelFor(String userId) {
        return dynamicChatModelFactory.model(appConfigurationService.resolve(userId, AppConfigurationService.CHAT));
    }

    /**
     * 批 2 闲聊短路的单轮直达路径：不走图编排，不建 agent_run，
     * 直接流式回答并按既有链路持久化消息（runId 为空，token 台账不含闲聊轮）。
     */
    private SseEmitter casualChat(CurrentActor actor, String conversationId, String message, long userMessageId,
                                  AutoCloseable conversationLease, String prefaceActivity) {
        SseEmitter emitter = new SseEmitter(java.util.concurrent.TimeUnit.MINUTES.toMillis(2));
        agentExecutor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("activity").data(prefaceActivity));
                StringBuilder answer = new StringBuilder();
                ChatClient.builder(chatModelFor(actor.userId())).build()
                        .prompt().user(message).stream().content()
                        .toStream()
                        .forEach(chunk -> {
                            if (chunk == null || chunk.isEmpty()) return;
                            answer.append(chunk);
                            try {
                                emitter.send(chunk);
                            } catch (Exception sendError) {
                                throw new IllegalStateException(sendError);
                            }
                        });
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
                completeManusRun(actor, conversationId, userMessageId, null, "{}", null,
                        new ManusGraphOrchestrator.ManusRunResult(answer.toString(), List.of(), true, "COMPLETED", null));
            } catch (Exception exception) {
                try {
                    emitter.send(SseEmitter.event().name("generation-error")
                            .data(com.zwx.zwxagent.common.ErrorMessages.describe(exception)));
                    emitter.send(SseEmitter.event().data("回答失败：" + com.zwx.zwxagent.common.ErrorMessages.describe(exception)));
                    emitter.send(SseEmitter.event().data("[DONE]"));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.complete();
                }
                completeManusRun(actor, conversationId, userMessageId, null, "{}", null,
                        new ManusGraphOrchestrator.ManusRunResult("", List.of(), false, "INTERRUPTED",
                                exception.getMessage()));
            } finally {
                try {
                    conversationLease.close();
                } catch (Exception ignored) {
                }
            }
        });
        return emitter;
    }

    private void manageManusEmitter(SseEmitter emitter, com.zwx.zwxagent.agent.graph.RunStream stream) {
        java.util.concurrent.ScheduledFuture<?>[] heartbeatHolder = new java.util.concurrent.ScheduledFuture<?>[1];
        heartbeatHolder[0] = HEARTBEAT_SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().name("ping").data("keep-alive"));
            } catch (Exception exception) {
                java.util.concurrent.ScheduledFuture<?> self = heartbeatHolder[0];
                if (self != null) self.cancel(false);
                stream.detach(emitter);
            }
        }, 15, 15, java.util.concurrent.TimeUnit.SECONDS);
        java.util.concurrent.ScheduledFuture<?> heartbeat = heartbeatHolder[0];
        emitter.onCompletion(() -> {
            heartbeat.cancel(false);
            if (!stream.completed()) stream.detach(emitter);
        });
        emitter.onTimeout(() -> {
            heartbeat.cancel(false);
            stream.detach(emitter);
        });
        emitter.onError(error -> {
            heartbeat.cancel(false);
            stream.detach(emitter);
        });
    }

    private void completeManusRun(CurrentActor actor, String conversationId, long userMessageId, String runId,
                                  String ragTraceJson, Long assistantMessageId,
                                  ManusGraphOrchestrator.ManusRunResult result) {
        try {
            if (result.completed()) {
                agentConversationService.completeUserTurn(actor.tenantId(), actor.userId(), userMessageId);
            } else {
                agentConversationService.markUserTurnInterrupted(actor.tenantId(), actor.userId(), userMessageId);
            }
            String filesJson = toJson(manusAttachments(result.activities(), toolFactoryScope(conversationId)));
            Long existingMessageId = assistantMessageId;
            if (existingMessageId == null) {
                existingMessageId = agentConversationService.findAssistantReplyId(
                        actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId, userMessageId);
            }
            long messageId;
            if (existingMessageId != null) {
                boolean updated = agentConversationService.updateAssistantReply(
                        actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId,
                        existingMessageId, result.answer(), result.status(), filesJson);
                messageId = existingMessageId;
                if (!updated) {
                    messageId = agentConversationService.appendAssistantReply(
                            actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId, userMessageId,
                            result.answer(), result.status(), runId, ragTraceJson, filesJson);
                }
            } else {
                messageId = agentConversationService.appendAssistantReply(
                        actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId, userMessageId,
                        result.answer(), result.status(), runId, ragTraceJson, filesJson);
            }
            agentRunService.finish(runId, result.status(), result.answer(), result.error(), messageId);
        } catch (RuntimeException persistenceError) {
            org.slf4j.LoggerFactory.getLogger(ManusAgentController.class)
                    .error("Failed to persist super agent turn; conversationId={}, runId={}", conversationId, runId, persistenceError);
        }
    }

    @PostMapping("/manus/conversations")
    public AgentConversationSummary createManusConversation(CurrentActor actor,
                                                             @RequestBody(required = false) CreateConversationRequest request) {
        String projectId = request == null ? null : request.projectId();
        return agentConversationService.createConversation(
                actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, SUPER_DEFAULT_TITLE, projectId);
    }

    @GetMapping("/manus/conversations")
    public List<AgentConversationSummary> listManusConversations(CurrentActor actor) {
        return agentConversationService.listConversations(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY);
    }

    public record CreateConversationRequest(String projectId) {
    }

    @GetMapping("/manus/conversations/{conversationId}/messages")
    public List<AgentConversationMessage> getManusMessages(CurrentActor actor, @PathVariable String conversationId) {
        return agentConversationService.getMessages(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId);
    }

    @DeleteMapping("/manus/conversations/{conversationId}")
    public void deleteManusConversation(CurrentActor actor, @PathVariable String conversationId) {
        agentConversationService.deleteConversation(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId);
    }

    @GetMapping("/manus/files")
    public ResponseEntity<FileSystemResource> getManusFile(CurrentActor actor,
                                                            @RequestParam String conversationId, @RequestParam String path) throws java.io.IOException {
        if (!agentConversationService.hasConversation(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId)) {
            return ResponseEntity.notFound().build();
        }
        Path file = resolveConversationFile(actor.tenantId(), conversationId, path);
        if (file == null || !Files.isRegularFile(file)) return ResponseEntity.notFound().build();
        String detectedType = Files.probeContentType(file);
        MediaType mediaType = detectedType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(detectedType);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.getFileName().toString(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(file));
    }

    /** 会话文件解析：优先统一工作区，回退旧工具目录（历史会话兼容）。 */
    private Path resolveConversationFile(String tenantId, String conversationId, String path) {
        try {
            Path workspaceRoot = workspaceService.conversationRoot(tenantId, SUPER_AGENT_KEY, conversationId);
            Path file = workspaceService.resolveWithin(workspaceRoot, path);
            if (Files.isRegularFile(file)) return file;
        } catch (Exception ignored) {
        }
        try {
            Path legacyRoot = toolSandbox.scopeDir(conversationId);
            Path file = legacyRoot.resolve(path).normalize();
            if (file.startsWith(legacyRoot) && Files.isRegularFile(file)) return file;
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 会话工作区文件清单（design/plans/09 批次 A）。 */
    @GetMapping("/workspace/files")
    public List<WorkspaceService.FileEntry> listWorkspaceFiles(
            CurrentActor actor, @RequestParam String agentKey, @RequestParam String conversationId,
            @RequestParam(defaultValue = "200") int limit) {
        if (!agentConversationService.hasConversation(actor.tenantId(), actor.userId(), agentKey, conversationId)) {
            return List.of();
        }
        return workspaceService.listFiles(actor.tenantId(), agentKey, conversationId, Math.min(Math.max(limit, 1), 500));
    }
}
