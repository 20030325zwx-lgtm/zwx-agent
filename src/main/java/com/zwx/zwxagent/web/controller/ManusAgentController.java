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
import com.zwx.zwxagent.knowledge.retrieval.AgentKnowledgeRagResult;
import com.zwx.zwxagent.knowledge.retrieval.AgentKnowledgeRagService;
import com.zwx.zwxagent.knowledge.storage.AgentImageStorageService;
import com.zwx.zwxagent.platform.intent.IntentRouter;
import com.zwx.zwxagent.platform.security.CurrentActor;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ManusAgentController.class);

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
    private com.zwx.zwxagent.agent.execution.PromptVersionService promptVersionService;

    @Resource
    private com.zwx.zwxagent.platform.workspace.WorkspaceFileExportService workspaceFileExportService;

    @Resource
    private com.zwx.zwxagent.platform.workspace.ProjectExecutionLockService projectExecutionLockService;

    @Resource
    private com.zwx.zwxagent.platform.workspace.RunWorkspaceService runWorkspaceService;

    @Resource
    private com.zwx.zwxagent.platform.workspace.ProjectWorkspaceService projectWorkspaceService;

    @Resource
    private com.zwx.zwxagent.platform.settings.ModelCatalogService modelCatalogService;

    @Resource
    private DynamicChatModelFactory dynamicChatModelFactory;

    @Resource
    private AgentImageStorageService agentImageStorageService;

    @Resource
    private com.zwx.zwxagent.knowledge.image.VisionAnalysisService visionAnalysisService;

    @Resource
    private AgentConversationService agentConversationService;

    @Resource
    private com.zwx.zwxagent.capability.mcp.McpServerConfigurationService mcpServerConfigurationService;

    @Resource
    private AgentKnowledgeRagService agentKnowledgeRagService;

    @Resource
    private ObjectMapper objectMapper;

    // 意图路由 v2（2026-11-08）：独立 LLM 意图分类器退出主链路（参考 dsh/ZCode：无前置分类器），
    // 编排分档改为 OrchestrationRouting 的硬规则 + 确定性匹配；IntentRouter 接口保留为评测平台与
    // 未来分档器复活的槽位（provider=none 时不装配）。

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
    private com.zwx.zwxagent.capability.tools.PermissionService permissionService;

    @Resource
    private com.zwx.zwxagent.agent.execution.RunTrajectoryService runTrajectoryService;

    @Resource
    private com.zwx.zwxagent.agent.observability.AgentMetrics agentMetrics;

    @Resource
    private RunQuotaService runQuotaService;
    @Resource
    private com.zwx.zwxagent.agent.execution.AgentRunStateService agentRunStateService;
    @Resource
    private com.zwx.zwxagent.agent.execution.store.AgentRunInputStore agentRunInputStore;
    @Resource
    private com.zwx.zwxagent.agent.execution.store.AgentRunCheckpointStore checkpointStore;

    /** 单循环档（DirectNode）默认开关：关闭后全量回到图编排档（零代码回滚）。 */
    @org.springframework.beans.factory.annotation.Value("${app.agent.single-loop.enabled:true}")
    private boolean singleLoopEnabled = true;
    @Resource
    private com.zwx.zwxagent.agent.execution.AgentExecutionTraceService agentExecutionTraceService;

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
                                      @RequestParam(required = false) String clientRequestId,
                                      @RequestParam(required = false) Long modelRowId,
                                      @RequestParam(required = false) String permissionMode) {
        agentConversationService.ensureConversation(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId, SUPER_DEFAULT_TITLE, message);
        // 每用户配额：并发 run / 小时频率 / 24h token 预算，超限 429（在会话锁之前，快速拒绝）
        runQuotaService.checkRunAllowed(actor.userId());
        // 权限模式：请求参数覆盖用户默认；非法值在进入执行前拒绝，避免半途失败。
        com.zwx.zwxagent.capability.tools.PermissionMode effectivePermissionMode =
                permissionMode == null || permissionMode.isBlank()
                        ? permissionService.defaultMode(actor.tenantId(), actor.userId())
                        : com.zwx.zwxagent.capability.tools.PermissionMode.require(permissionMode);
        // 输入框里选择的模型只解析一次：闲聊直达与图编排共用同一份选择
        var modelSelection = chatSelection(actor.userId(), modelRowId);
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
        // 意图分流发生在检索/工具装配之前。知识库和联网均完全服从用户开关。
        // 禁用/无 Key/失败时维持用户开关，行为与未引入该功能一致。
        boolean effectiveKnowledgeSearch = knowledgeSearch;
        boolean effectiveWebSearch = webSearch;
        String prefaceActivity = null;
        boolean workspaceRequest = false;
        if (com.zwx.zwxagent.platform.intent.WorkspaceRequestDetector.referencesWorkspace(message)) {
            try {
                workspaceRequest = projectWorkspaceService
                        .resolveForConversation(actor.tenantId(), actor.userId(), conversationId)
                        .isPresent();
            } catch (RuntimeException exception) {
                agentConversationService.markUserTurnInterrupted(actor.tenantId(), actor.userId(), userMessageId);
                conversationLease.close();
                throw exception;
            }
        }
        // 编排路由 v2：无前置 LLM 分类。用户显式信号（工作区/带图）与确定性并行证据 → 图编排；
        // 其余默认单循环（直答是单循环的自然结果，不再前置分类）。
        var routing = com.zwx.zwxagent.platform.intent.OrchestrationRouting.decide(
                workspaceRequest, !imageObjectKeys.isEmpty(), message, singleLoopEnabled);
        boolean singleLoop = routing.singleLoop();
        prefaceActivity = toJson(java.util.Map.of("phase", "plan", "agent", "编排路由",
                "summary", routing.reason() + "（知识库/联网开关保持用户选择）"));
        if (singleLoop && agentMetrics != null) agentMetrics.orchestrationSingleLoop();
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
                        // 对象存储已切内网 MinIO：改为字节直传视觉模型，不再让第三方回源拉图
                        byte[] imageBytes = agentImageStorageService.readBytes(SUPER_AGENT_KEY, conversationId, key);
                        var analysis = visionAnalysisService.analyze(actor.userId(), imageBytes,
                                com.zwx.zwxagent.knowledge.storage.AgentImageStorageService.mimeTypeOf(key), message);
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
            historyContext = agentConversationService
                    .getRecentMessages(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId, 21).stream()
                    .filter(historyMessage -> historyMessage.id() != userMessageId)
                    .map(historyMessage -> {
                        String role = "USER".equals(historyMessage.role()) ? "用户" : "助手";
                        String status = "INTERRUPTED".equals(historyMessage.status()) ? "（上一轮未完成，仅保留部分内容）" : "";
                        return role + status + ": " + historyMessage.content();
                    })
                    .collect(java.util.stream.Collectors.joining("\n"));
            runChatModel = dynamicChatModelFactory.model(modelSelection.config());
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
                    userMessageId, agentMessage, historyContext, knowledgeContext, ragTraceJson,
                    effectivePermissionMode.name());
        } catch (RuntimeException exception) {
            agentConversationService.markUserTurnInterrupted(actor.tenantId(), actor.userId(), userMessageId);
            activeMcpTools.close();
            conversationLease.close();
            throw exception;
        }
        modelCatalogService.attachToRun(runId, modelSelection);
        recordPromptSnapshot(runId);
        try {
            availableTools = Stream.concat(
                            Arrays.stream(toolFactory.createTools(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY,
                                    conversationId, runId, effectiveWebSearch)),
                            Arrays.stream(activeMcpTools.callbacks()))
                    .toArray(ToolCallback[]::new);
        } catch (RuntimeException exception) {
            agentConversationService.markUserTurnInterrupted(actor.tenantId(), actor.userId(), userMessageId);
            agentRunService.finish(runId, "FAILED", null, exception.getMessage(), null);
            activeMcpTools.close();
            conversationLease.close();
            throw exception;
        }
        Runnable cleanup = () -> {
            projectExecutionLockService.release(runId);
            activeMcpTools.close();
            conversationLease.close();
        };
        ManusGraphOrchestrator.ManusRunRequest runRequest = new ManusGraphOrchestrator.ManusRunRequest(
                conversationId, actor.tenantId(), actor.userId(), runChatModel, agentMessage, historyContext, availableTools, knowledgeContext,
                knowledge.trace() == null ? null : ragTraceJson, prefaceActivity, runId, effectivePermissionMode.name(), null, null, cleanup,
                result -> completeManusRun(actor, conversationId, userMessageId, runId, ragTraceJson, null, result),
                null, singleLoop ? "single" : null);
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

    /** 工作台概览：跨会话任务动态（含会话标题与任务输入），供统计与时间线展示。 */
    @GetMapping("/manus/runs/overview")
    public List<AgentRunService.RunOverview> listManusRunOverviews(CurrentActor actor,
                                                                   @RequestParam(defaultValue = "30") int limit) {
        return agentRunService.findRecentRunOverviews(actor.tenantId(), actor.userId(),
                Math.min(Math.max(limit, 1), 50));
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
        return runView(actor, conversationId, runId);
    }

    /** 统一装配 run 视图：阶段、派工历史、提示词版本快照。 */
    private AgentRunView runView(CurrentActor actor, String conversationId, String runId) {
        com.zwx.zwxagent.agent.execution.AgentRun run = agentRunService
                .findScoped(actor.tenantId(), actor.userId(), conversationId, runId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Run not found"));
        return AgentRunView.from(run, agentRunService.assignments(runId),
                AgentRunView.parsePromptVersions(run.promptVersionsJson()));
    }

    public record RunAnswerRequest(String answer) {
    }

    /**
     * 回答智能体的结构化提问（askHuman）：清空等待状态并返回最新视图。
     * 答复正文由前端作为新消息发送（新一轮会带上这句答复继续）。
     */
    @PostMapping("/manus/conversations/{conversationId}/runs/{runId}/answer")
    public AgentRunView answerManusRun(CurrentActor actor, @PathVariable String conversationId,
                                       @PathVariable String runId, @RequestBody RunAnswerRequest request) {
        com.zwx.zwxagent.agent.execution.AgentRun run = agentRunService
                .findScoped(actor.tenantId(), actor.userId(), conversationId, runId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Run not found"));
        if (!run.waitingForInput()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "该任务当前不在等待补充信息");
        }
        agentRunService.markInputAnswered(runId);
        return runView(actor, conversationId, runId);
    }

    /** 轨迹导出：检查点 + 工具账本 + 模型调用 + 执行事件，用于评测留证与回放。 */
    @GetMapping("/manus/conversations/{conversationId}/runs/{runId}/trajectory")
    public java.util.Map<String, Object> getManusRunTrajectory(CurrentActor actor,
                                                               @PathVariable String conversationId,
                                                               @PathVariable String runId) {
        return runTrajectoryService.trajectory(actor.tenantId(), actor.userId(), conversationId, runId)
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
                                              @PathVariable String runId,
                                              @org.springframework.web.bind.annotation.RequestBody(required = false) RunControlBody body) {
        agentRunService.findScoped(actor.tenantId(), actor.userId(), conversationId, runId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Run not found"));
        // A2：engine_version=2 的 run 走持久化控制请求（安全点生效），旧引擎维持在线取消
        var run = agentRunService.findScoped(actor.tenantId(), actor.userId(), conversationId, runId);
        if (run.isPresent() && run.get().engineV2()) {
            return controlRun(actor, conversationId, runId, body,
                    com.zwx.zwxagent.agent.execution.AgentRunStateService.CONTROL_CANCEL_REQUESTED);
        }
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

    // ------------------------------------------------------------------
    // 工作智能体整改 A2：engine_version=2 持久化运行接口（方案 §9）
    // 启动后由 AgentRunDispatcher 派发执行；SSE 仅为观察通道，断开不影响执行。
    // ------------------------------------------------------------------

    public record StartRunBody(String message, String modelRowId, String permissionMode,
                               String clientRequestId, Boolean webSearch, Boolean knowledgeSearch) {
    }

    public record RunControlBody(Long expectedVersion, String requestId) {
    }

    public record RunAnswerBody(String answer, String requestId) {
    }

    /** 启动 v2 运行：幂等（clientRequestId），立即返回 202，执行由派发器认领。 */
    @PostMapping("/manus/conversations/{conversationId}/runs")
    public java.util.Map<String, Object> startManusRunV2(
            com.zwx.zwxagent.platform.security.CurrentActor actor,
            @PathVariable String conversationId,
            @org.springframework.web.bind.annotation.RequestBody StartRunBody body) {
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "message is required");
        }
        if (body.clientRequestId() == null || body.clientRequestId().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "clientRequestId is required");
        }
        var existingRun = agentRunStateService.findByStartRequestKey(actor.tenantId(), actor.userId(),
                body.clientRequestId());
        if (existingRun.isPresent()) {
            // 启动幂等：重复请求返回既有 run，不再创建新的用户轮次
            return java.util.Map.of("runId", existingRun.get(), "duplicated", true);
        }
        runQuotaService.checkRunAllowed(actor.userId());
        agentConversationService.ensureConversation(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY,
                conversationId, SUPER_DEFAULT_TITLE, body.message());
        long userMessageId = agentConversationService.startUserTurn(actor.tenantId(), actor.userId(),
                SUPER_AGENT_KEY, conversationId, body.message(), List.of(), body.clientRequestId());
        String historyContext = agentConversationService
                .getRecentMessages(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId, 21).stream()
                .filter(historyMessage -> historyMessage.id() != userMessageId)
                .map(historyMessage -> ("USER".equals(historyMessage.role()) ? "用户" : "助手")
                        + ": " + historyMessage.content())
                .collect(java.util.stream.Collectors.joining("\n"));
        String knowledgeContext = "";
        if (Boolean.TRUE.equals(body.knowledgeSearch())) {
            knowledgeContext = agentKnowledgeRagService
                    .retrieveWithContext(actor.tenantId(), SUPER_AGENT_KEY, body.message()).context();
        }
        String permissionMode = body.permissionMode() == null || body.permissionMode().isBlank()
                ? com.zwx.zwxagent.capability.tools.PermissionMode.DEFAULT.name() : body.permissionMode();
        var command = new com.zwx.zwxagent.agent.execution.AgentRunStateService.StartCommand(
                conversationId, actor.tenantId(), actor.userId(), userMessageId, body.message(),
                historyContext, knowledgeContext, permissionMode, "INTERACTIVE", body.clientRequestId());
        var startResult = agentRunStateService.start(command, null);
        long assistantMessageId = agentConversationService.appendAssistantReply(actor.tenantId(), actor.userId(),
                SUPER_AGENT_KEY, conversationId, userMessageId, "", "IN_PROGRESS", startResult.runId(), null, null);
        return java.util.Map.of(
                "runId", startResult.runId(),
                "userMessageId", userMessageId,
                "assistantMessageId", assistantMessageId,
                "duplicated", startResult.duplicated());
    }

    /** v2 运行快照：状态、控制请求、事件游标、待处理输入。 */
    @GetMapping("/manus/conversations/{conversationId}/runs/{runId}/snapshot")
    public java.util.Map<String, Object> manusRunSnapshotV2(
            com.zwx.zwxagent.platform.security.CurrentActor actor,
            @PathVariable String conversationId, @PathVariable String runId) {
        com.zwx.zwxagent.agent.execution.AgentRun run = agentRunService.findScoped(actor.tenantId(), actor.userId(), conversationId, runId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Run not found"));
        return java.util.Map.of(
                "run", AgentRunView.from(run, agentRunService.assignments(runId), null),
                "eventCursor", agentRunStateService.eventCursor(runId),
                "stateVersion", agentRunStateService.stateVersion(runId, actor.tenantId(), actor.userId()).orElse(0L),
                "pendingInputs", agentRunInputStore.pendingOfRun(actor.tenantId(), actor.userId(), runId));
    }

    /** 持久化事件分页（keyset 游标）：跨重启回放的权威来源。 */
    @GetMapping("/manus/conversations/{conversationId}/runs/{runId}/events")
    public java.util.Map<String, Object> manusRunEventsV2(
            com.zwx.zwxagent.platform.security.CurrentActor actor,
            @PathVariable String conversationId, @PathVariable String runId,
            @RequestParam(defaultValue = "0") long afterSeq,
            @RequestParam(defaultValue = "100") int limit) {
        agentRunService.findScoped(actor.tenantId(), actor.userId(), conversationId, runId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Run not found"));
        var events = agentExecutionTraceService.listAfter(runId, actor.tenantId(), actor.userId(),
                afterSeq, limit);
        long nextCursor = events.isEmpty() ? afterSeq : events.get(events.size() - 1).sequence();
        return java.util.Map.of("events", events, "nextCursor", nextCursor,
                "hasMore", events.size() >= Math.max(1, Math.min(limit, 500)));
    }

    /** v2 暂停：写入控制请求，执行器在安全点停机（正在运行的动作不立即中断）。 */
    @PostMapping("/manus/conversations/{conversationId}/runs/{runId}/pause")
    public java.util.Map<String, Object> pauseManusRunV2(
            com.zwx.zwxagent.platform.security.CurrentActor actor,
            @PathVariable String conversationId, @PathVariable String runId,
            @org.springframework.web.bind.annotation.RequestBody(required = false) RunControlBody body) {
        return controlRun(actor, conversationId, runId, body,
                com.zwx.zwxagent.agent.execution.AgentRunStateService.CONTROL_PAUSE_REQUESTED);
    }

    private java.util.Map<String, Object> controlRun(
            com.zwx.zwxagent.platform.security.CurrentActor actor, String conversationId, String runId,
            RunControlBody body, String controlState) {
        agentRunService.findScoped(actor.tenantId(), actor.userId(), conversationId, runId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Run not found"));
        boolean accepted = agentRunStateService.requestControl(runId, actor.tenantId(), actor.userId(),
                controlState, body == null ? null : body.expectedVersion());
        if (!accepted) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Run is not in a controllable phase");
        }
        return java.util.Map.of("accepted", true, "controlState", controlState);
    }

    /** v2 恢复：用户暂停后的 run 重新入队（从检查点继续，不新建 run）。 */
    @PostMapping("/manus/conversations/{conversationId}/runs/{runId}/resume")
    public java.util.Map<String, Object> resumeManusRunV2(
            com.zwx.zwxagent.platform.security.CurrentActor actor,
            @PathVariable String conversationId, @PathVariable String runId) {
        agentRunService.findScoped(actor.tenantId(), actor.userId(), conversationId, runId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Run not found"));
        if (!agentRunStateService.resume(runId, actor.tenantId(), actor.userId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Run is not resumable in v2 state machine");
        }
        return java.util.Map.of("accepted", true, "phase", "QUEUED");
    }

    /** v2 同任务续答：答复只消费一次，原 run 重新入队，不新建 run（方案 §5.3）。 */
    @PostMapping("/manus/conversations/{conversationId}/runs/{runId}/inputs/{inputId}/answer")
    public java.util.Map<String, Object> answerManusRunV2(
            com.zwx.zwxagent.platform.security.CurrentActor actor,
            @PathVariable String conversationId, @PathVariable String runId, @PathVariable java.util.UUID inputId,
            @org.springframework.web.bind.annotation.RequestBody RunAnswerBody body) {
        com.zwx.zwxagent.agent.execution.AgentRun run = agentRunService.findScoped(actor.tenantId(), actor.userId(), conversationId, runId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Run not found"));
        if (body == null || body.answer() == null || body.answer().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "answer is required");
        }
        if (body.requestId() == null || body.requestId().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "requestId is required");
        }
        var question = agentRunInputStore.pendingOfRun(actor.tenantId(), actor.userId(), runId).stream()
                .filter(input -> input.id().equals(inputId)
                        && com.zwx.zwxagent.agent.execution.store.AgentRunInputStore.KIND_QUESTION.equals(input.kind()))
                .findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "No pending question with this id"));
        var answerInput = agentRunInputStore.record(actor.tenantId(), actor.userId(), conversationId, runId,
                question.stepId(), com.zwx.zwxagent.agent.execution.store.AgentRunInputStore.KIND_ANSWER,
                java.util.Map.of("answer", body.answer(), "questionInputId", inputId.toString()),
                body.requestId(), inputId, null);
        if (!agentRunInputStore.accept(actor.tenantId(), actor.userId(), question.id(), question.version())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Question already answered");
        }
        agentRunInputStore.markApplied(actor.tenantId(), actor.userId(), answerInput.id(), null);
        if (!agentRunStateService.requeueAfterAnswer(runId, actor.tenantId(), actor.userId(), body.answer())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Run is not waiting for input");
        }
        return java.util.Map.of("accepted", true, "answerInputId", answerInput.id().toString(),
                "runId", run.id());
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
                            Arrays.stream(toolFactory.createTools(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY,
                                    conversationId, runId, webSearch)),
                            Arrays.stream(mcpTools.callbacks()))
                    .toArray(ToolCallback[]::new);
            runChatModel = chatModelFor(actor.userId());
        } catch (RuntimeException exception) {
            if (mcpTools != null) mcpTools.close();
            conversationLease.close();
            throw exception;
        }
        McpTools activeMcpTools = mcpTools;
        // v2 追加式快照优先（权威 + hash 校验），无快照或校验不符时回退 agent_run 行内列（展示缓存）
        com.zwx.zwxagent.agent.graph.GraphCheckpoint checkpoint = checkpointStore
                .latestGraphCheckpoint(actor.tenantId(), actor.userId(), runId)
                .orElseGet(() -> run.checkpoint() == null
                        ? new com.zwx.zwxagent.agent.graph.GraphCheckpoint(
                                null, null, List.of(), List.of(), List.of(), List.of(), 0, null, null, null, null)
                        : run.checkpoint());
        String resumeFrom = com.zwx.zwxagent.agent.graph.ManusGraphOrchestrator.resumeNode(checkpoint);
        boolean resumeSingleLoop = com.zwx.zwxagent.agent.graph.ManusGraphOrchestrator.NODE_DIRECT.equals(checkpoint.lastNode());
        org.slf4j.LoggerFactory.getLogger(ManusAgentController.class).debug(
                "resume manus run; runId={}, lastNode={}, resumeFrom={}, planSize={}, resultSize={}",
                runId, checkpoint.lastNode(), resumeFrom, checkpoint.plan().size(), checkpoint.taskResults().size());
        com.zwx.zwxagent.agent.graph.GraphCheckpoint resumeCheckpoint =
                com.zwx.zwxagent.agent.graph.ManusGraphOrchestrator.prepareResumeCheckpoint(checkpoint, resumeFrom);
        if (resumeCheckpoint == null) resumeCheckpoint = checkpoint;
        agentRunService.restart(runId);
        String ragTraceJson = run.ragTraceJson() == null ? "{}" : run.ragTraceJson();
        Runnable cleanup = () -> {
            projectExecutionLockService.release(runId);
            activeMcpTools.close();
            conversationLease.close();
        };
        com.zwx.zwxagent.agent.graph.ManusGraphOrchestrator.ManusRunRequest runRequest =
                new com.zwx.zwxagent.agent.graph.ManusGraphOrchestrator.ManusRunRequest(
                        conversationId, actor.tenantId(), actor.userId(), runChatModel, run.input(),
                        run.historyContext() == null ? "" : run.historyContext(),
                        availableTools, run.knowledgeContext() == null ? "" : run.knowledgeContext(),
                        run.ragTraceJson(), null, runId,
                        com.zwx.zwxagent.capability.tools.PermissionMode.parse(run.permissionMode()).name(),
                        resumeCheckpoint, resumeFrom, cleanup,
                        result -> completeManusRun(actor, conversationId, run.userMessageId(), runId,
                                ragTraceJson, run.assistantMessageId(), result), null,
                        resumeSingleLoop ? "single" : null);
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
        return dynamicChatModelFactory.model(chatSelection(userId, null).config());
    }

    private com.zwx.zwxagent.platform.settings.ModelCatalogService.ResolvedSelection chatSelection(String userId, Long modelRowId) {
        return modelRowId == null
                // 场景级绑定（专用覆盖通用）：默认模型走「智能体对话」绑定，未绑定回退通用对话
                ? modelCatalogService.resolveWithFallback(userId,
                        com.zwx.zwxagent.platform.settings.ModelCatalogService.PURPOSE_AGENT,
                        com.zwx.zwxagent.platform.settings.ModelCatalogService.PURPOSE_CHAT)
                : modelCatalogService.resolveModelRow(userId, modelRowId);
    }

    /**
     * 批 2 闲聊短路的单轮直达路径：不走图编排，不建 agent_run，
     * 直接流式回答并按既有链路持久化消息（runId 为空，token 台账不含闲聊轮）。
     */
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
        } finally {
            projectExecutionLockService.release(runId);
            runWorkspaceService.finish(runId, result.status());
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

    /** 导出会话工作区文件到桌面端已授权的本机目录（仅桌面端；返回真实绝对路径供「在访达中显示」）。 */
    public record ExportFilesRequest(String conversationId, List<String> paths, String targetDir, Boolean overwrite) {
    }

    @PostMapping("/manus/files/export")
    public List<com.zwx.zwxagent.platform.workspace.WorkspaceFileExportService.ExportResult> exportManusFiles(
            CurrentActor actor, @RequestBody ExportFilesRequest request) throws java.io.IOException {
        if (request == null || request.conversationId() == null
                || !agentConversationService.hasConversation(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, request.conversationId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "会话不存在");
        }
        try {
            return workspaceFileExportService.export(actor.tenantId(), SUPER_AGENT_KEY, request.conversationId(),
                    request.paths(), request.targetDir(), Boolean.TRUE.equals(request.overwrite()));
        } catch (IllegalStateException | IllegalArgumentException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, exception.getMessage());
        }
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

    /** run 开始时的提示词版本快照（0 = 内置默认），便于事后追溯。 */
    private void recordPromptSnapshot(String runId) {
        try {
            java.util.Map<String, Integer> versions = promptVersionService.snapshot(
                    java.util.Map.of(
                            com.zwx.zwxagent.agent.execution.PromptVersionService.PLANNER, "",
                            com.zwx.zwxagent.agent.execution.PromptVersionService.VERIFIER, "",
                            com.zwx.zwxagent.agent.execution.PromptVersionService.AGGREGATOR, ""));
            agentRunService.recordPromptVersions(runId, toJson(versions));
        } catch (Exception exception) {
            log.debug("Unable to record prompt versions for run {}: {}", runId, exception.getMessage());
        }
    }
}
