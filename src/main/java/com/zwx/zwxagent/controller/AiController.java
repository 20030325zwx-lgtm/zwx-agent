package com.zwx.zwxagent.controller;

import com.zwx.zwxagent.agent.ZwxManus;
import com.zwx.zwxagent.app.LoveApp;
import com.zwx.zwxagent.conversation.LoveConversationMessage;
import com.zwx.zwxagent.conversation.LoveConversationService;
import com.zwx.zwxagent.conversation.LoveConversationSummary;
import com.zwx.zwxagent.conversation.AgentConversationMessage;
import com.zwx.zwxagent.conversation.AgentConversationService;
import com.zwx.zwxagent.conversation.AgentConversationSummary;
import com.zwx.zwxagent.execution.AgentExecutionEvent;
import com.zwx.zwxagent.execution.AgentExecutionTraceService;
import com.zwx.zwxagent.execution.ExecutionUpdate;
import com.zwx.zwxagent.security.CurrentActor;
import com.zwx.zwxagent.storage.LoveImageStorageService;
import com.zwx.zwxagent.storage.LoveImageUpload;
import com.zwx.zwxagent.storage.LoveKnowledgeDocumentStorageService;
import com.zwx.zwxagent.storage.LoveKnowledgeDocumentUpload;
import com.zwx.zwxagent.rag.LoveKnowledgeReference;
import com.zwx.zwxagent.rag.LoveRagResult;
import com.zwx.zwxagent.rag.LoveRagService;
import com.zwx.zwxagent.rag.LoveRagTrace;
import com.zwx.zwxagent.rag.LoveKnowledgeAdminService;
import com.zwx.zwxagent.rag.LoveKnowledgeDocumentDetail;
import com.zwx.zwxagent.rag.LoveKnowledgeDocumentSummary;
import com.zwx.zwxagent.rag.LoveKnowledgeIndexJob;
import com.zwx.zwxagent.rag.LoveKnowledgeIndexService;
import com.zwx.zwxagent.rag.AgentKnowledgeDocument;
import com.zwx.zwxagent.rag.AgentKnowledgeDocumentService;
import com.zwx.zwxagent.rag.AgentKnowledgeDocumentDetail;
import com.zwx.zwxagent.app.TravelPlannerApp;
import com.zwx.zwxagent.app.TestAgentApp;
import com.zwx.zwxagent.rag.AgentKnowledgeRagService;
import com.zwx.zwxagent.rag.AgentKnowledgeRagResult;
import com.zwx.zwxagent.skills.BuiltInSkillRegistry;
import com.zwx.zwxagent.skills.SkillConfigurationRequest;
import com.zwx.zwxagent.skills.SkillCatalogItem;
import com.zwx.zwxagent.app.LoveVisionChatResult;
import com.zwx.zwxagent.constant.FileConstant;
import com.zwx.zwxagent.mcp.McpConnectionTestResult;
import com.zwx.zwxagent.mcp.McpServerConfiguration;
import com.zwx.zwxagent.mcp.McpServerConfigurationRequest;
import com.zwx.zwxagent.mcp.McpServerConfigurationService;
import com.zwx.zwxagent.mcp.McpTools;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.codec.ServerSentEvent;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
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
import java.util.UUID;
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
    private LoveApp loveApp;

    @Resource
    private com.zwx.zwxagent.tools.ToolFactory toolFactory;

    @Resource
    private com.zwx.zwxagent.tools.ToolSandbox toolSandbox;

    @Resource
    private ChatModel dashscopeChatModel;

    @Resource
    private LoveConversationService conversationService;

    @Resource
    private LoveImageStorageService imageStorageService;

    @Resource
    private LoveKnowledgeDocumentStorageService knowledgeDocumentStorageService;

    @Resource
    private LoveRagService loveRagService;

    @Resource
    private LoveKnowledgeAdminService loveKnowledgeAdminService;

    @Resource
    private LoveKnowledgeIndexService loveKnowledgeIndexService;

    @Resource
    private AgentKnowledgeDocumentService agentKnowledgeDocumentService;

    @Resource
    private AgentConversationService agentConversationService;

    @Resource
    private AgentExecutionTraceService agentExecutionTraceService;

    @Resource
    private McpServerConfigurationService mcpServerConfigurationService;

    @Resource
    private TravelPlannerApp travelPlannerApp;

    @Resource
    private TestAgentApp testAgentApp;

    @Resource
    private AgentKnowledgeRagService agentKnowledgeRagService;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private com.zwx.zwxagent.conversation.ConversationLockManager conversationLockManager;

    @Resource(name = "agentExecutor")
    private java.util.concurrent.Executor agentExecutor;

    @Resource
    private BuiltInSkillRegistry builtInSkillRegistry;

    @Resource
    private com.zwx.zwxagent.rag.QueryRewriter queryRewriter;

    @Resource(name = "ragExecutor")
    private java.util.concurrent.Executor ragExecutor;

    @org.springframework.beans.factory.annotation.Value("${spring.ai.dashscope.chat.options.model:qwen-plus}")
    private String chatModelName;

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

    @PostMapping("/love_app/conversations")
    public LoveConversationSummary createLoveConversation(CurrentActor actor) {
        return conversationService.createConversation(actor);
    }

    @GetMapping("/love_app/conversations")
    public List<LoveConversationSummary> listLoveConversations(CurrentActor actor) {
        return conversationService.listConversations(actor);
    }

    @GetMapping("/love_app/conversations/{conversationId}/messages")
    public List<LoveConversationMessage> getLoveConversationMessages(CurrentActor actor, @PathVariable String conversationId) {
        return conversationService.getMessages(actor, conversationId);
    }

    @DeleteMapping("/love_app/conversations/{conversationId}/messages/{userMessageId}/assistant")
    public boolean deleteLoveAssistantReply(CurrentActor actor, @PathVariable String conversationId, @PathVariable long userMessageId) {
        return conversationService.deleteAssistantReply(actor, conversationId, userMessageId);
    }

    @DeleteMapping("/love_app/conversations/{conversationId}")
    public void deleteLoveConversation(CurrentActor actor, @PathVariable String conversationId) {
        conversationService.deleteConversation(actor, conversationId);
    }

    @PostMapping(value = "/love_app/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LoveImageUpload uploadLoveImage(CurrentActor actor, @RequestParam String chatId, @RequestPart("file") MultipartFile file) {
        conversationService.ensureOwnedConversation(actor, chatId, "图片消息");
        return imageStorageService.upload(chatId, file);
    }

    @PostMapping("/love_app/knowledge/documents/upload")
    public List<LoveKnowledgeDocumentUpload> uploadLoveKnowledgeDocuments(CurrentActor actor) {
        actor.requireAdmin();
        return knowledgeDocumentStorageService.uploadBundledDocuments();
    }

    @PostMapping("/love_app/knowledge/index/built-in")
    public LoveKnowledgeIndexJob indexBuiltInLoveKnowledge(CurrentActor actor) {
        actor.requireAdmin();
        LoveKnowledgeIndexJob job = loveKnowledgeIndexService.createBundledDocumentIndexJob();
        loveKnowledgeIndexService.indexBundledDocuments(job.id());
        return job;
    }

    @GetMapping("/love_app/knowledge/index/jobs/{jobId}")
    public LoveKnowledgeIndexJob getLoveKnowledgeIndexJob(CurrentActor actor, @PathVariable String jobId) {
        actor.requireAdmin();
        return loveKnowledgeIndexService.getJob(jobId);
    }

    @PostMapping(value = "/agent-knowledge/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AgentKnowledgeDocument uploadAgentKnowledgeDocument(CurrentActor actor, @RequestParam String agentKey, @RequestPart("file") MultipartFile file) {
        actor.requireAdmin();
        AgentKnowledgeDocument document = agentKnowledgeDocumentService.upload(actor.tenantId(), agentKey, file);
        agentKnowledgeDocumentService.indexDocument(document.id());
        return document;
    }

    @GetMapping("/agent-knowledge/documents")
    public List<AgentKnowledgeDocument> listAgentKnowledgeDocuments(CurrentActor actor, @RequestParam String agentKey) {
        return agentKnowledgeDocumentService.listDocuments(actor.tenantId(), agentKey);
    }

    @GetMapping("/agent-knowledge/documents/{documentId}")
    public AgentKnowledgeDocumentDetail getAgentKnowledgeDocument(CurrentActor actor, @PathVariable String documentId, @RequestParam String agentKey) {
        actor.requireAdmin();
        return agentKnowledgeDocumentService.getDocument(actor.tenantId(), agentKey, documentId);
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

    @GetMapping("/love_app/knowledge/references")
    public List<LoveKnowledgeReference> findLoveKnowledgeReferences(@RequestParam String message) {
        return loveRagService.trace(message, chatModelName).references();
    }

    @GetMapping("/love_app/knowledge/documents")
    public List<LoveKnowledgeDocumentSummary> listLoveKnowledgeDocuments(CurrentActor actor) {
        actor.requireAdmin();
        return loveKnowledgeAdminService.listDocuments();
    }

    @GetMapping("/love_app/knowledge/document")
    public LoveKnowledgeDocumentDetail getLoveKnowledgeDocument(CurrentActor actor, @RequestParam String objectKey) {
        actor.requireAdmin();
        return loveKnowledgeAdminService.getDocument(objectKey);
    }

    @GetMapping("/love_app/images")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> getLoveImage(
            CurrentActor actor, @RequestParam String chatId, @RequestParam String objectKey) {
        if (!conversationService.ownsConversation(actor, chatId)) {
            return ResponseEntity.notFound().build();
        }
        var response = imageStorageService.getImage(chatId, objectKey);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(response.contentType()))
                .body(new org.springframework.core.io.InputStreamResource(response.inputStream()));
    }

    @GetMapping("/love_app/chat/sync")
    public String doChatWithLoveAppSync(CurrentActor actor, String message, String chatId) {
        return loveApp.doChat(actor, message, chatId);
    }

    @GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> doChatWithLoveAppSSE(CurrentActor actor, String message, String chatId,
                                                               @RequestParam(required = false) List<String> imageKey,
                                                               @RequestParam(defaultValue = "false") boolean webSearch,
                                                               @RequestParam(required = false) Long retryUserMessageId,
                                                               @RequestParam(required = false) String clientRequestId,
                                                               @RequestParam(required = false) Long continueFromMessageId) {
        conversationService.ensureOwnedConversation(actor, chatId, message);
        conversationService.requireNoDuplicateRequest(actor, chatId, clientRequestId);
        if (!conversationLockManager.tryLock(chatId)) throw new com.zwx.zwxagent.conversation.ConversationBusyException();
        List<String> imageObjectKeys = imageKey == null ? List.of() : imageKey;
        try {
            Flux<ServerSentEvent<String>> stream;
            if (continueFromMessageId != null) {
                stream = doContinuationChatWithLoveAppSSE(actor, chatId, continueFromMessageId, webSearch);
            } else if (!imageObjectKeys.isEmpty()) {
                stream = doVisionChatWithLoveAppSSE(actor, message, chatId, imageObjectKeys, retryUserMessageId, clientRequestId);
            } else {
                stream = doTextChatWithLoveAppSSE(actor, message, chatId, webSearch, retryUserMessageId, clientRequestId);
            }
            return withHeartbeat(stream.doFinally(signal -> conversationLockManager.unlock(chatId)));
        } catch (RuntimeException exception) {
            conversationLockManager.unlock(chatId);
            throw exception;
        }
    }

    private Flux<ServerSentEvent<String>> doTextChatWithLoveAppSSE(CurrentActor actor, String message, String chatId,
                                                                    boolean webSearch, Long retryUserMessageId, String clientRequestId) {
        return Flux.concat(
                Mono.just(ServerSentEvent.<String>builder().event("thinking").data("正在分析你的问题与对话上下文...").build()),
                Mono.just(ServerSentEvent.<String>builder().event("thinking").data("正在检索情感知识库与私有资料...").build()),
                Mono.fromCallable(() -> prepareLoveTextChat(actor, message))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(chat -> Flux.concat(
                                Mono.just(ServerSentEvent.<String>builder().event("thinking").data("已完成资料检索，正在生成分析建议...").build()),
                                loveApp.doChatByStream(actor, message, chatId, chat.context(), webSearch, retryUserMessageId, clientRequestId).map(chunk -> ServerSentEvent.builder(chunk).build()),
                                Mono.fromRunnable(() -> conversationService.saveLatestAssistantRagData(actor, chatId, chat.referencesJson(), chat.traceJson()))
                                        .thenReturn(ServerSentEvent.<String>builder().event("thinking").data("正在整理引用与会话记录...").build()),
                                Mono.just(ServerSentEvent.<String>builder().event("trace").data(chat.traceJson()).build()),
                                Mono.just(ServerSentEvent.<String>builder().event("references").data(chat.referencesJson()).build()),
                                Mono.just(ServerSentEvent.<String>builder("[DONE]").build()))))
                .onErrorResume(error -> Flux.just(
                        ServerSentEvent.<String>builder().event("generation-error").data(com.zwx.zwxagent.util.ErrorMessages.describe(error)).build(),
                        ServerSentEvent.<String>builder("[DONE]").build()));
    }

    private Flux<ServerSentEvent<String>> doContinuationChatWithLoveAppSSE(CurrentActor actor, String chatId, long assistantMessageId, boolean webSearch) {
        String draft = conversationService.getInterruptedAssistantDraft(actor, assistantMessageId);
        String retrievalQuery = draft.length() > 200 ? draft.substring(draft.length() - 200) : draft;
        return Flux.concat(
                Mono.just(ServerSentEvent.<String>builder().event("thinking").data("正在加载未完成的草稿与上下文...").build()),
                Mono.fromCallable(() -> prepareLoveTextChat(actor, retrievalQuery))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(chat -> Flux.concat(
                                Mono.just(ServerSentEvent.<String>builder().event("thinking").data("正在从中断处继续生成...").build()),
                                loveApp.doChatContinuationByStream(actor, chatId, assistantMessageId, chat.context(), webSearch, null)
                                        .map(chunk -> ServerSentEvent.builder(chunk).build()),
                                Mono.fromRunnable(() -> conversationService.saveAssistantRagDataFor(actor, assistantMessageId, chat.referencesJson(), chat.traceJson()))
                                        .thenReturn(ServerSentEvent.<String>builder().event("thinking").data("正在整理引用与会话记录...").build()),
                                Mono.just(ServerSentEvent.<String>builder().event("trace").data(chat.traceJson()).build()),
                                Mono.just(ServerSentEvent.<String>builder().event("references").data(chat.referencesJson()).build()),
                                Mono.just(ServerSentEvent.<String>builder("[DONE]").build()))))
                .onErrorResume(error -> Flux.just(
                        ServerSentEvent.<String>builder().event("generation-error").data(com.zwx.zwxagent.util.ErrorMessages.describe(error)).build(),
                        ServerSentEvent.<String>builder("[DONE]").build()));
    }

    private Flux<ServerSentEvent<String>> withHeartbeat(Flux<ServerSentEvent<String>> source) {
        return source.publish(shared -> {
            Flux<ServerSentEvent<String>> heartbeat = Flux.interval(java.time.Duration.ofSeconds(15))
                    .map(index -> ServerSentEvent.<String>builder().event("ping").data("keep-alive").build())
                    .onBackpressureDrop();
            return Flux.merge(shared, heartbeat.takeUntilOther(shared.ignoreElements().then()));
        });
    }

    private LoveTextChat prepareLoveTextChat(CurrentActor actor, String message) {
        String searchQuery = rewrittenQuery(message);
        LoveRagResult ragResult = loveRagService.retrieve(searchQuery, chatModelName);
        AgentKnowledgeRagResult privateKnowledge = agentKnowledgeRagService.retrieveWithContext(actor.tenantId(), "love", searchQuery);
        LoveRagTrace trace = mergePrivateReferences(ragResult.trace(), privateKnowledge);
        try {
            String referencesJson = objectMapper.writeValueAsString(trace.references());
            String traceJson = objectMapper.writeValueAsString(trace);
            return new LoveTextChat(ragResult.context() + "\n" + privateKnowledge.context(), referencesJson, traceJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize knowledge references", exception);
        }
    }

    private String rewrittenQuery(String message) {
        // 口语化短句改写为更适合向量检索的查询；改写是加时器敏感路径，超预算则退回原始消息
        if (message == null || message.isBlank() || message.length() > 200) return message;
        try {
            return java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> queryRewriter.doQueryRewrite(message), ragExecutor)
                    .get(1200, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            return message;
        }
    }

    private LoveRagTrace mergePrivateReferences(LoveRagTrace trace, AgentKnowledgeRagResult privateKnowledge) {
        if (privateKnowledge.references().isEmpty()) return trace;
        List<LoveKnowledgeReference> references = new ArrayList<>(trace.references());
        references.addAll(privateKnowledge.references());
        return new LoveRagTrace(trace.query(), trace.topK(), trace.similarityThreshold(), trace.candidates(), trace.decision(),
                references, trace.model(), trace.streaming(), trace.degraded());
    }

    private Flux<ServerSentEvent<String>> doVisionChatWithLoveAppSSE(CurrentActor actor, String message, String chatId, List<String> imageObjectKeys, Long retryUserMessageId, String clientRequestId) {
        return Flux.concat(
                Mono.just(ServerSentEvent.<String>builder().event("thinking").data("正在理解图片内容...").build()),
                Mono.fromCallable(() -> loveApp.prepareVisionChat(actor, message, chatId, imageObjectKeys))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(result -> Flux.concat(
                                Mono.just(ServerSentEvent.<String>builder().event("vision")
                                        .data(toJson(result.analysis())).build()),
                                Mono.just(ServerSentEvent.<String>builder().event("thinking")
                                        .data(result.analysis().available()
                                                ? "已提取图片线索，正在检索相关资料..."
                                                : "图片线索暂不可用，正在继续生成分析...").build()),
                                loveApp.streamVisionChat(actor, message, chatId, imageObjectKeys, result, retryUserMessageId, clientRequestId)
                                        .map(chunk -> ServerSentEvent.builder(chunk).build()),
                                toVisionEvents(result))));
    }

    private Flux<ServerSentEvent<String>> toVisionEvents(LoveVisionChatResult result) {
        try {
            String referencesJson = objectMapper.writeValueAsString(result.ragTrace().references());
            String traceJson = objectMapper.writeValueAsString(result.ragTrace());
            return Flux.concat(
                    Mono.just(ServerSentEvent.<String>builder().event("thinking").data("正在整理引用与会话记录...").build()),
                    Mono.just(ServerSentEvent.<String>builder().event("trace").data(traceJson).build()),
                    Mono.just(ServerSentEvent.<String>builder().event("references").data(referencesJson).build()),
                    Mono.just(ServerSentEvent.<String>builder("[DONE]").build()));
        } catch (JsonProcessingException exception) {
            return Flux.error(new IllegalStateException("Unable to serialize vision RAG data", exception));
        }
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

    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(CurrentActor actor, @RequestParam String conversationId, @RequestParam String message,
                                      @RequestParam(defaultValue = "false") boolean knowledgeSearch) {
        agentConversationService.ensureConversation(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId, SUPER_DEFAULT_TITLE, message);
        if (!conversationLockManager.tryLock(conversationId)) throw new com.zwx.zwxagent.conversation.ConversationBusyException();
        AgentKnowledgeRagResult knowledge = knowledgeSearch
                ? agentKnowledgeRagService.retrieveWithContext(actor.tenantId(), SUPER_AGENT_KEY, message)
                : new AgentKnowledgeRagResult("", List.of());
        McpTools mcpTools = mcpServerConfigurationService.toolsFor(actor.tenantId());
        ToolCallback[] availableTools = Stream.concat(Arrays.stream(toolFactory.createTools(conversationId)), Arrays.stream(mcpTools.callbacks()))
                .toArray(ToolCallback[]::new);
        ZwxManus zwxManus = new ZwxManus(availableTools, dashscopeChatModel, knowledge.context());
        zwxManus.restoreHistory(agentConversationService.getRecentMessages(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId, 20));
        SseEmitter emitter = zwxManus.runStream(message, result -> agentConversationService.saveCompletedTurn(
                actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId, SUPER_DEFAULT_TITLE, message, result.answer(), toJson(manusAttachments(result.activities(), toolFactoryScope(conversationId)))), agentExecutor);
        java.util.concurrent.ScheduledFuture<?>[] heartbeatHolder = new java.util.concurrent.ScheduledFuture<?>[1];
        heartbeatHolder[0] = HEARTBEAT_SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().name("ping").data("keep-alive"));
            } catch (Exception exception) {
                java.util.concurrent.ScheduledFuture<?> self = heartbeatHolder[0];
                if (self != null) self.cancel(false);
            }
        }, 15, 15, java.util.concurrent.TimeUnit.SECONDS);
        java.util.concurrent.ScheduledFuture<?> heartbeat = heartbeatHolder[0];
        Runnable stopHeartbeat = () -> heartbeat.cancel(false);
        emitter.onCompletion(() -> {
            stopHeartbeat.run();
            mcpTools.close();
            conversationLockManager.unlock(conversationId);
        });
        emitter.onTimeout(() -> {
            stopHeartbeat.run();
            mcpTools.close();
            conversationLockManager.unlock(conversationId);
        });
        emitter.onError(error -> {
            stopHeartbeat.run();
            zwxManus.stopForClientDisconnect();
            mcpTools.close();
            conversationLockManager.unlock(conversationId);
        });
        return emitter;
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
        Path scopeRoot = toolSandbox.scopeDir(conversationId);
        Path file = scopeRoot.resolve(path).normalize();
        if (!file.startsWith(scopeRoot) || !Files.isRegularFile(file)) return ResponseEntity.notFound().build();
        String detectedType = Files.probeContentType(file);
        MediaType mediaType = detectedType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(detectedType);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.getFileName().toString(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(file));
    }

    @GetMapping(value = "/travel-planner/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatWithTravelPlanner(CurrentActor actor, @RequestParam String conversationId, String message,
                                                                @RequestParam(defaultValue = "false") boolean webSearch, @RequestParam(required = false) Long retryUserMessageId,
                                                                @RequestParam(required = false) String clientRequestId) {
        agentConversationService.ensureTravelConversation(actor.tenantId(), actor.userId(), conversationId, message);
        agentConversationService.requireNoDuplicateRequest(actor.tenantId(), actor.userId(), clientRequestId);
        if (!conversationLockManager.tryLock(conversationId)) throw new com.zwx.zwxagent.conversation.ConversationBusyException();
        String runId = UUID.randomUUID().toString();
        Flux<ServerSentEvent<String>> travelStream = Flux.<ServerSentEvent<String>>create(sink -> {
            java.util.function.Consumer<ExecutionUpdate> progress = update -> {
                int sequence;
                try {
                    sequence = agentExecutionTraceService.record(runId, actor.tenantId(), "travel", conversationId, update.phase(), update.summary(), update.detail());
                } catch (Exception persistenceError) {
                    sequence = -1;
                }
                sink.next(ServerSentEvent.<String>builder(toJson(Map.of("runId", runId, "sequence", sequence, "phase", update.phase(), "summary", update.summary()))).event("activity").build());
                sink.next(ServerSentEvent.<String>builder(update.summary()).event("thinking").build());
            };
            progress.accept(new ExecutionUpdate("received", "正在接收并理解你的旅行需求...", Map.of("message", message)));
            reactor.core.Disposable subscription = travelPlannerApp.chat(actor.tenantId(), actor.userId(), conversationId, runId, message, retryUserMessageId, webSearch, progress,
                    references -> sink.next(ServerSentEvent.<String>builder(toJson(references)).event("references").build()), clientRequestId).subscribe(
                    chunk -> sink.next(ServerSentEvent.builder(chunk).build()),
                    error -> {
                        sink.next(ServerSentEvent.<String>builder().event("generation-error").data(com.zwx.zwxagent.util.ErrorMessages.describe(error)).build());
                        sink.next(ServerSentEvent.<String>builder("[DONE]").build());
                        sink.complete();
                    },
                    () -> {
                        sink.next(ServerSentEvent.<String>builder("[DONE]").build());
                        sink.complete();
                    });
            sink.onCancel(() -> {
                subscription.dispose();
                conversationLockManager.unlock(conversationId);
                agentExecutionTraceService.record(runId, actor.tenantId(), "travel", conversationId, "interrupted",
                        "连接中断：已生成内容将保留，可重新提问继续。", Map.of("runId", runId));
            });
        }).doFinally(signal -> conversationLockManager.unlock(conversationId));
        return withHeartbeat(travelStream);
    }

    @GetMapping("/travel-planner/conversations/{conversationId}/executions/{runId}")
    public List<AgentExecutionEvent> getTravelExecutionEvents(CurrentActor actor, @PathVariable String conversationId, @PathVariable String runId) {
        if (!agentConversationService.hasConversation(actor.tenantId(), actor.userId(), "travel", conversationId)) {
            return List.of();
        }
        return agentExecutionTraceService.listTravelEvents(actor.tenantId(), conversationId, runId);
    }

    @PostMapping("/travel-planner/conversations")
    public AgentConversationSummary createTravelConversation(CurrentActor actor) {
        return agentConversationService.createTravelConversation(actor.tenantId(), actor.userId());
    }

    @GetMapping("/travel-planner/conversations")
    public List<AgentConversationSummary> listTravelConversations(CurrentActor actor) {
        return agentConversationService.listTravelConversations(actor.tenantId(), actor.userId());
    }

    @GetMapping("/travel-planner/conversations/{conversationId}/messages")
    public List<AgentConversationMessage> getTravelConversationMessages(CurrentActor actor, @PathVariable String conversationId) {
        return agentConversationService.getTravelMessages(actor.tenantId(), actor.userId(), conversationId);
    }

    @DeleteMapping("/travel-planner/conversations/{conversationId}/messages/{userMessageId}/assistant")
    public boolean deleteTravelAssistantReply(CurrentActor actor, @PathVariable String conversationId, @PathVariable long userMessageId) {
        String runId = agentConversationService.deleteTravelAssistantReply(actor.tenantId(), actor.userId(), conversationId, userMessageId);
        if (runId != null) agentExecutionTraceService.deleteRun(actor.tenantId(), conversationId, runId);
        return runId != null;
    }

    @DeleteMapping("/travel-planner/conversations/{conversationId}")
    public void deleteTravelConversation(CurrentActor actor, @PathVariable String conversationId) {
        agentConversationService.deleteTravelConversation(actor.tenantId(), actor.userId(), conversationId);
    }

    @GetMapping(value = "/test-agent/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatWithTestAgent(CurrentActor actor, @RequestParam String conversationId,
                                                            @RequestParam String message, @RequestParam(defaultValue = "false") boolean webSearch, @RequestParam(required = false) Long retryUserMessageId,
                                                            @RequestParam(required = false) String clientRequestId) {
        agentConversationService.requireNoDuplicateRequest(actor.tenantId(), actor.userId(), clientRequestId);
        if (!conversationLockManager.tryLock(conversationId)) throw new com.zwx.zwxagent.conversation.ConversationBusyException();
        return withHeartbeat(testAgentApp.chat(actor.tenantId(), actor.userId(), conversationId, message, webSearch, retryUserMessageId, references -> {}, clientRequestId)
                .map(ServerSentEvent::builder).map(ServerSentEvent.Builder::build)
                .concatWithValues(ServerSentEvent.<String>builder("[DONE]").build())
                .onErrorResume(error -> Flux.just(
                        ServerSentEvent.<String>builder().event("generation-error").data(com.zwx.zwxagent.util.ErrorMessages.describe(error)).build(),
                        ServerSentEvent.<String>builder("[DONE]").build()))
                .doFinally(signal -> conversationLockManager.unlock(conversationId)));
    }

    @PostMapping("/test-agent/conversations")
    public AgentConversationSummary createTestConversation(CurrentActor actor) {
        return agentConversationService.createConversation(actor.tenantId(), actor.userId(), "test", "新的功能测试");
    }
    @GetMapping("/test-agent/conversations")
    public List<AgentConversationSummary> listTestConversations(CurrentActor actor) {
        return agentConversationService.listConversations(actor.tenantId(), actor.userId(), "test");
    }
    @GetMapping("/test-agent/conversations/{conversationId}/messages")
    public List<AgentConversationMessage> getTestMessages(CurrentActor actor, @PathVariable String conversationId) {
        return agentConversationService.getMessages(actor.tenantId(), actor.userId(), "test", conversationId);
    }
    @DeleteMapping("/test-agent/conversations/{conversationId}")
    public void deleteTestConversation(CurrentActor actor, @PathVariable String conversationId) {
        agentConversationService.deleteConversation(actor.tenantId(), actor.userId(), "test", conversationId);
    }
    @DeleteMapping("/test-agent/conversations/{conversationId}/messages/{userMessageId}/assistant")
    public boolean deleteTestAssistantReply(CurrentActor actor, @PathVariable String conversationId, @PathVariable long userMessageId) {
        return agentConversationService.deleteAssistantReply(actor.tenantId(), actor.userId(), "test", conversationId, userMessageId) != null;
    }

    private record LoveTextChat(String context, String referencesJson, String traceJson) {
    }
}
