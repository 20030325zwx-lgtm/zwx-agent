package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.agent.execution.AgentRunSummary;
import com.zwx.zwxagent.agent.execution.AgentRunView;
import com.zwx.zwxagent.agent.graph.ManusGraphOrchestrator;
import com.zwx.zwxagent.platform.settings.AppConfigurationService;
import com.zwx.zwxagent.platform.settings.DynamicChatModelFactory;
import com.zwx.zwxagent.chat.conversation.AgentConversationMessage;
import com.zwx.zwxagent.chat.conversation.AgentConversationService;
import com.zwx.zwxagent.chat.conversation.AgentConversationSummary;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.knowledge.document.AgentKnowledgeDocument;
import com.zwx.zwxagent.knowledge.document.AgentKnowledgeDocumentService;
import com.zwx.zwxagent.knowledge.document.AgentKnowledgeDocumentDetail;
import com.zwx.zwxagent.knowledge.retrieval.AgentKnowledgeRagService;
import com.zwx.zwxagent.knowledge.retrieval.AgentKnowledgeRagResult;
import com.zwx.zwxagent.capability.skills.BuiltInSkillRegistry;
import com.zwx.zwxagent.capability.skills.SkillConfigurationRequest;
import com.zwx.zwxagent.capability.skills.SkillCatalogItem;
import com.zwx.zwxagent.capability.skills.SkillRepository;
import com.zwx.zwxagent.capability.mcp.McpConnectionTestResult;
import com.zwx.zwxagent.capability.mcp.McpServerConfiguration;
import com.zwx.zwxagent.capability.mcp.McpServerConfigurationRequest;
import com.zwx.zwxagent.capability.mcp.McpServerConfigurationService;
import com.zwx.zwxagent.capability.mcp.McpTools;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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

@RestController
@RequestMapping("/ai")
public class AiController {

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
    private com.zwx.zwxagent.capability.tools.ToolFactory toolFactory;

    @Resource
    private com.zwx.zwxagent.capability.tools.ToolSandbox toolSandbox;

    @Resource
    private com.zwx.zwxagent.platform.workspace.WorkspaceService workspaceService;

    @Resource
    private AppConfigurationService appConfigurationService;

    @Resource
    private DynamicChatModelFactory dynamicChatModelFactory;

    @Resource
    private com.zwx.zwxagent.knowledge.storage.AgentImageStorageService agentImageStorageService;

    @Resource
    private com.zwx.zwxagent.knowledge.image.ImageAnalysisService imageAnalysisService;

    @Resource
    private AgentKnowledgeDocumentService agentKnowledgeDocumentService;

    @Resource
    private AgentConversationService agentConversationService;

    @Resource
    private McpServerConfigurationService mcpServerConfigurationService;

    @Resource
    private AgentKnowledgeRagService agentKnowledgeRagService;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private com.zwx.zwxagent.chat.conversation.ConversationLockManager conversationLockManager;

    @Resource(name = "agentExecutor")
    private java.util.concurrent.Executor agentExecutor;

    @Resource
    private com.zwx.zwxagent.agent.graph.ManusGraphOrchestrator manusGraphOrchestrator;

    @Resource
    private com.zwx.zwxagent.agent.execution.AgentRunService agentRunService;

    @Resource
    private BuiltInSkillRegistry builtInSkillRegistry;

    @Resource
    private SkillRepository skillRepository;

    @Resource(name = "ragExecutor")
    private java.util.concurrent.Executor ragExecutor;

    @GetMapping("/skills/catalog")
    public List<SkillCatalogItem> listSkillCatalog(CurrentActor actor, @RequestParam String agentKey) {
        return builtInSkillRegistry.catalogWithConfiguration(actor.tenantId(), agentKey);
    }

    @PostMapping("/skills/config")
    public List<SkillCatalogItem> saveSkillConfiguration(CurrentActor actor, @RequestBody SkillConfigurationRequest request) {
        actor.requireAdmin();
        if (request == null || request.agentKey() == null || request.enabledSkillIds() == null) throw new IllegalArgumentException("Invalid Skill configuration");
        builtInSkillRegistry.saveConfiguration(actor.tenantId(), request.agentKey(), request.enabledSkillIds());
        return builtInSkillRegistry.catalogWithConfiguration(actor.tenantId(), request.agentKey());
    }

    @PostMapping("/skills/reload")
    public List<SkillCatalogItem> reloadSkills(CurrentActor actor, @RequestParam String agentKey) {
        actor.requireAdmin();
        skillRepository.reload();
        return builtInSkillRegistry.catalogWithConfiguration(actor.tenantId(), agentKey);
    }

    @PostMapping(value = "/agent-knowledge/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AgentKnowledgeDocument uploadAgentKnowledgeDocument(CurrentActor actor, @RequestParam String agentKey,
                                                               @RequestPart("file") MultipartFile file,
                                                               @RequestParam(required = false) String categoryId) {
        actor.requireAdmin();
        AgentKnowledgeDocument document = agentKnowledgeDocumentService.upload(actor.tenantId(), agentKey, file, actor.username(), categoryId);
        agentKnowledgeDocumentService.indexDocument(document.id());
        return document;
    }

    @GetMapping("/agent-knowledge/documents")
    public List<AgentKnowledgeDocument> listAgentKnowledgeDocuments(CurrentActor actor, @RequestParam String agentKey) {
        return agentKnowledgeDocumentService.listDocuments(actor.tenantId(), agentKey);
    }

    @GetMapping("/agent-knowledge/categories")
    public List<com.zwx.zwxagent.knowledge.document.AgentKnowledgeCategory> listAgentKnowledgeCategories(CurrentActor actor,
                                                                                          @RequestParam String agentKey) {
        return agentKnowledgeDocumentService.listCategories(actor.tenantId(), agentKey);
    }

    @PostMapping("/agent-knowledge/categories")
    public com.zwx.zwxagent.knowledge.document.AgentKnowledgeCategory createAgentKnowledgeCategory(
            CurrentActor actor, @RequestBody CreateKnowledgeCategoryRequest request) {
        actor.requireAdmin();
        return agentKnowledgeDocumentService.createCategory(actor.tenantId(), request.agentKey(), request.name(), actor.username());
    }

    @PostMapping("/agent-knowledge/documents/{documentId}/category")
    public AgentKnowledgeDocument moveAgentKnowledgeDocument(CurrentActor actor, @PathVariable String documentId,
                                                             @RequestParam String agentKey,
                                                             @RequestParam String categoryId) {
        actor.requireAdmin();
        return agentKnowledgeDocumentService.moveDocument(actor.tenantId(), agentKey, documentId, categoryId);
    }

    public record CreateKnowledgeCategoryRequest(String agentKey, String name) {
    }

    @GetMapping("/agent-knowledge/chunks/{chunkId}")
    public com.zwx.zwxagent.knowledge.document.AgentKnowledgeChunkPreview getAgentKnowledgeChunk(
            CurrentActor actor, @PathVariable String chunkId, @RequestParam String agentKey) {
        return agentKnowledgeDocumentService.getChunk(actor.tenantId(), agentKey, chunkId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Knowledge chunk not found"));
    }

    @GetMapping("/agent-knowledge/documents/{documentId}")
    public AgentKnowledgeDocumentDetail getAgentKnowledgeDocument(CurrentActor actor, @PathVariable String documentId, @RequestParam String agentKey) {
        actor.requireAdmin();
        return agentKnowledgeDocumentService.getDocument(actor.tenantId(), agentKey, documentId);
    }

    @GetMapping("/agent-knowledge/documents/{documentId}/content")
    public ResponseEntity<byte[]> getAgentKnowledgeDocumentContent(CurrentActor actor, @PathVariable String documentId,
                                                                   @RequestParam String agentKey) {
        actor.requireAdmin();
        var content = agentKnowledgeDocumentService.readDocumentFile(actor.tenantId(), agentKey, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(content.filename(), StandardCharsets.UTF_8).build().toString())
                .body(content.bytes());
    }

    @PostMapping("/agent-knowledge/documents/{documentId}/publish")
    public AgentKnowledgeDocument publishAgentKnowledgeDocument(CurrentActor actor, @PathVariable String documentId,
                                                                 @RequestParam String agentKey) {
        actor.requireAdmin();
        agentKnowledgeDocumentService.getDocumentRecordForScope(actor.tenantId(), agentKey, documentId);
        agentKnowledgeDocumentService.publish(documentId);
        return agentKnowledgeDocumentService.getDocumentRecordForScope(actor.tenantId(), agentKey, documentId);
    }

    @PostMapping("/agent-knowledge/documents/{documentId}/reindex")
    public AgentKnowledgeDocument reindexAgentKnowledgeDocument(CurrentActor actor, @PathVariable String documentId, @RequestParam String agentKey) {
        actor.requireAdmin();
        AgentKnowledgeDocument document = agentKnowledgeDocumentService.getDocumentRecordForScope(actor.tenantId(), agentKey, documentId);
        agentKnowledgeDocumentService.indexDocument(documentId);
        return document;
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/agent-knowledge/documents/{documentId}")
    public void deleteAgentKnowledgeDocument(CurrentActor actor, @PathVariable String documentId, @RequestParam String agentKey) {
        actor.requireAdmin();
        agentKnowledgeDocumentService.deleteDocument(actor.tenantId(), agentKey, documentId);
    }

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
                                      @RequestParam(required = false) String imageObjectKey,
                                      @RequestParam(required = false) String clientRequestId) {
        agentConversationService.ensureConversation(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId, SUPER_DEFAULT_TITLE, message);
        var conversationLease = conversationLockManager.tryAcquire(actor.tenantId() + ":" + actor.userId() + ":super:" + conversationId);
        if (conversationLease == null) throw new com.zwx.zwxagent.chat.conversation.ConversationBusyException();
        long userMessageId;
        List<String> imageObjectKeys = imageObjectKey == null || imageObjectKey.isBlank()
                ? List.of() : List.of(imageObjectKey);
        try {
            if (!imageObjectKeys.isEmpty()) {
                agentImageStorageService.validateObjectKey(SUPER_AGENT_KEY, conversationId, imageObjectKey);
            }
            // 先落 USER 轮次：即使进程随后崩溃，下一轮历史里仍能看到这次提问和图片。
            userMessageId = agentConversationService.startUserTurn(
                    actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId, message, imageObjectKeys, clientRequestId);
        } catch (RuntimeException exception) {
            conversationLease.close();
            throw exception;
        }
        AgentKnowledgeRagResult knowledge;
        McpTools mcpTools = null;
        ToolCallback[] availableTools;
        String historyContext;
        ChatModel runChatModel;
        String imageContext = "";
        String retrievalQuery = message;
        try {
            if (imageObjectKey != null && !imageObjectKey.isBlank()) {
                agentImageStorageService.validateObjectKey(SUPER_AGENT_KEY, conversationId, imageObjectKey);
                String imageUrl = agentImageStorageService.presignedReadUrl(SUPER_AGENT_KEY, conversationId, imageObjectKey);
                var analysis = imageAnalysisService.analyze(actor.tenantId(), imageUrl, message);
                if (analysis.available()) {
                    imageContext = analysis.contextBlock();
                    retrievalQuery = analysis.queryOr(message);
                } else {
                    imageContext = "[图片分析失败：" + analysis.error() + "]\n";
                }
            }
            knowledge = knowledgeSearch
                    ? agentKnowledgeRagService.retrieveWithContext(actor.tenantId(), SUPER_AGENT_KEY, retrievalQuery)
                    : new AgentKnowledgeRagResult("", List.of());
            mcpTools = mcpServerConfigurationService.toolsFor(actor.tenantId());
            availableTools = Stream.concat(Arrays.stream(toolFactory.createTools(actor.tenantId(), SUPER_AGENT_KEY, conversationId)), Arrays.stream(mcpTools.callbacks()))
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
                knowledge.trace() == null ? null : ragTraceJson, runId, null, null, cleanup,
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

    @GetMapping("/manus/conversations/{conversationId}/runs/{runId}/resume")
    public SseEmitter resumeManusRun(CurrentActor actor, @PathVariable String conversationId, @PathVariable String runId) {
        com.zwx.zwxagent.agent.execution.AgentRun run = agentRunService
                .findScoped(actor.tenantId(), actor.userId(), conversationId, runId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Run not found"));
        if (!run.resumable()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "This run has already finished");
        }
        var conversationLease = conversationLockManager.tryAcquire(
                actor.tenantId() + ":" + actor.userId() + ":super:" + conversationId);
        if (conversationLease == null) throw new com.zwx.zwxagent.chat.conversation.ConversationBusyException();
        McpTools mcpTools = null;
        ToolCallback[] availableTools;
        ChatModel runChatModel;
        try {
            mcpTools = mcpServerConfigurationService.toolsFor(actor.tenantId());
            availableTools = Stream.concat(
                            Arrays.stream(toolFactory.createTools(actor.tenantId(), SUPER_AGENT_KEY, conversationId)),
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
        org.slf4j.LoggerFactory.getLogger(AiController.class).debug(
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
                        run.ragTraceJson(), runId, resumeCheckpoint, resumeFrom, cleanup,
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
            org.slf4j.LoggerFactory.getLogger(AiController.class)
                    .error("Failed to persist super agent turn; conversationId={}, runId={}", conversationId, runId, persistenceError);
        }
    }

    @GetMapping("/mcp/servers")
    public List<McpServerConfiguration> listMcpServers(CurrentActor actor) {
        return mcpServerConfigurationService.list(actor.tenantId());
    }

    @PostMapping("/mcp/servers")
    public McpServerConfiguration createMcpServer(CurrentActor actor, @RequestBody McpServerConfigurationRequest request) {
        actor.requireAdmin();
        return mcpServerConfigurationService.create(actor.tenantId(), request);
    }

    @PutMapping("/mcp/servers/{id}")
    public McpServerConfiguration updateMcpServer(CurrentActor actor, @PathVariable long id, @RequestBody McpServerConfigurationRequest request) {
        actor.requireAdmin();
        return mcpServerConfigurationService.update(actor.tenantId(), id, request);
    }

    @DeleteMapping("/mcp/servers/{id}")
    public void deleteMcpServer(CurrentActor actor, @PathVariable long id) {
        actor.requireAdmin();
        mcpServerConfigurationService.delete(actor.tenantId(), id);
    }

    @PostMapping("/mcp/servers/{id}/test")
    public McpConnectionTestResult testMcpServer(CurrentActor actor, @PathVariable long id) {
        actor.requireAdmin();
        return mcpServerConfigurationService.test(actor.tenantId(), id);
    }

    @PostMapping("/manus/conversations")
    public AgentConversationSummary createManusConversation(CurrentActor actor) {
        return agentConversationService.createConversation(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, SUPER_DEFAULT_TITLE);
    }

    @GetMapping("/manus/conversations")
    public List<AgentConversationSummary> listManusConversations(CurrentActor actor) {
        return agentConversationService.listConversations(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY);
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
    public List<com.zwx.zwxagent.platform.workspace.WorkspaceService.FileEntry> listWorkspaceFiles(
            CurrentActor actor, @RequestParam String agentKey, @RequestParam String conversationId,
            @RequestParam(defaultValue = "200") int limit) {
        if (!agentConversationService.hasConversation(actor.tenantId(), actor.userId(), agentKey, conversationId)) {
            return List.of();
        }
        return workspaceService.listFiles(actor.tenantId(), agentKey, conversationId, Math.min(Math.max(limit, 1), 500));
    }


    @org.springframework.web.bind.annotation.PostMapping("/rag-evaluations/{messageId}/judgments")
    public ResponseEntity<Void> judgeRagCandidate(CurrentActor actor, @PathVariable long messageId,
                                                   @org.springframework.web.bind.annotation.RequestBody RagJudgmentRequest request) {
        boolean saved = agentConversationService.saveRagJudgment(actor.tenantId(), actor.userId(), request.agentKey(),
                messageId, request.candidateId(), request.judgment());
        return saved ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    public record RagJudgmentRequest(String agentKey, String candidateId, String judgment) { }
}
