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
import com.zwx.zwxagent.storage.LoveImageStorageService;
import com.zwx.zwxagent.storage.LoveImageUpload;
import com.zwx.zwxagent.storage.LoveKnowledgeDocumentStorageService;
import com.zwx.zwxagent.storage.LoveKnowledgeDocumentUpload;
import com.zwx.zwxagent.rag.LoveKnowledgeReference;
import com.zwx.zwxagent.rag.LoveRagService;
import com.zwx.zwxagent.rag.LoveRagTrace;
import com.zwx.zwxagent.rag.LoveKnowledgeAdminService;
import com.zwx.zwxagent.rag.LoveKnowledgeDocumentDetail;
import com.zwx.zwxagent.rag.LoveKnowledgeDocumentSummary;
import com.zwx.zwxagent.rag.LoveKnowledgeIndexJob;
import com.zwx.zwxagent.rag.LoveKnowledgeIndexService;
import com.zwx.zwxagent.rag.LoveRagResult;
import com.zwx.zwxagent.rag.AgentKnowledgeDocument;
import com.zwx.zwxagent.rag.AgentKnowledgeDocumentService;
import com.zwx.zwxagent.rag.AgentKnowledgeDocumentDetail;
import com.zwx.zwxagent.app.TravelPlannerApp;
import com.zwx.zwxagent.rag.AgentKnowledgeRagService;
import com.zwx.zwxagent.rag.AgentKnowledgeRagResult;
import com.zwx.zwxagent.app.LoveVisionChatResult;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private LoveApp loveApp;

    @Resource
    private ToolCallback[] allTools;

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
    private TravelPlannerApp travelPlannerApp;

    @Resource
    private AgentKnowledgeRagService agentKnowledgeRagService;

    @Resource
    private ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${spring.ai.dashscope.chat.options.model:qwen-plus}")
    private String chatModelName;

    @PostMapping("/love_app/conversations")
    public LoveConversationSummary createLoveConversation() {
        return conversationService.createConversation();
    }

    @GetMapping("/love_app/conversations")
    public List<LoveConversationSummary> listLoveConversations() {
        return conversationService.listConversations();
    }

    @GetMapping("/love_app/conversations/{conversationId}/messages")
    public List<LoveConversationMessage> getLoveConversationMessages(@PathVariable String conversationId) {
        return conversationService.getMessages(conversationId);
    }

    @DeleteMapping("/love_app/conversations/{conversationId}/messages/{userMessageId}/assistant")
    public boolean deleteLoveAssistantReply(@PathVariable String conversationId, @PathVariable long userMessageId) {
        return conversationService.deleteAssistantReply(conversationId, userMessageId);
    }

    @DeleteMapping("/love_app/conversations/{conversationId}")
    public void deleteLoveConversation(@PathVariable String conversationId) {
        conversationService.deleteConversation(conversationId);
    }

    @PostMapping(value = "/love_app/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LoveImageUpload uploadLoveImage(@RequestParam String chatId, @RequestPart("file") MultipartFile file) {
        return imageStorageService.upload(chatId, file);
    }

    @PostMapping("/love_app/knowledge/documents/upload")
    public List<LoveKnowledgeDocumentUpload> uploadLoveKnowledgeDocuments() {
        return knowledgeDocumentStorageService.uploadBundledDocuments();
    }

    @PostMapping("/love_app/knowledge/index/built-in")
    public LoveKnowledgeIndexJob indexBuiltInLoveKnowledge() {
        LoveKnowledgeIndexJob job = loveKnowledgeIndexService.createBundledDocumentIndexJob();
        loveKnowledgeIndexService.indexBundledDocuments(job.id());
        return job;
    }

    @GetMapping("/love_app/knowledge/index/jobs/{jobId}")
    public LoveKnowledgeIndexJob getLoveKnowledgeIndexJob(@PathVariable String jobId) {
        return loveKnowledgeIndexService.getJob(jobId);
    }

    @PostMapping(value = "/agent-knowledge/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AgentKnowledgeDocument uploadAgentKnowledgeDocument(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                                               @RequestParam String agentKey, @RequestPart("file") MultipartFile file) {
        AgentKnowledgeDocument document = agentKnowledgeDocumentService.upload(tenantId, agentKey, file);
        agentKnowledgeDocumentService.indexDocument(document.id());
        return document;
    }

    @GetMapping("/agent-knowledge/documents")
    public List<AgentKnowledgeDocument> listAgentKnowledgeDocuments(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                                                     @RequestParam String agentKey) {
        return agentKnowledgeDocumentService.listDocuments(tenantId, agentKey);
    }

    @GetMapping("/agent-knowledge/documents/{documentId}")
    public AgentKnowledgeDocumentDetail getAgentKnowledgeDocument(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                                                   @PathVariable String documentId, @RequestParam String agentKey) {
        return agentKnowledgeDocumentService.getDocument(tenantId, agentKey, documentId);
    }

    @GetMapping("/love_app/knowledge/references")
    public List<LoveKnowledgeReference> findLoveKnowledgeReferences(@RequestParam String message) {
        return loveRagService.trace(message, chatModelName).references();
    }

    @GetMapping("/love_app/knowledge/documents")
    public List<LoveKnowledgeDocumentSummary> listLoveKnowledgeDocuments() {
        return loveKnowledgeAdminService.listDocuments();
    }

    @GetMapping("/love_app/knowledge/document")
    public LoveKnowledgeDocumentDetail getLoveKnowledgeDocument(@RequestParam String objectKey) {
        return loveKnowledgeAdminService.getDocument(objectKey);
    }

    @GetMapping("/love_app/images")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.InputStreamResource> getLoveImage(
            @RequestParam String chatId, @RequestParam String objectKey) {
        var response = imageStorageService.getImage(chatId, objectKey);
        return org.springframework.http.ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(response.contentType()))
                .body(new org.springframework.core.io.InputStreamResource(response.inputStream()));
    }

    /**
     * 同步调用 AI 恋爱大师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping("/love_app/chat/sync")
    public String doChatWithLoveAppSync(String message, String chatId) {
        return loveApp.doChat(message, chatId);
    }

    /**
     * SSE 流式调用 AI 恋爱大师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> doChatWithLoveAppSSE(String message, String chatId,
                                                               @RequestParam(required = false) List<String> imageKey,
                                                               @RequestParam(required = false) Long retryUserMessageId,
                                                               @RequestParam(defaultValue = "default") String tenantId) {
        validateTenantId(tenantId);
        List<String> imageObjectKeys = imageKey == null ? List.of() : imageKey;
        if (!imageObjectKeys.isEmpty()) {
            return doVisionChatWithLoveAppSSE(message, chatId, imageObjectKeys, tenantId, retryUserMessageId);
        }
        return Flux.concat(
                Mono.just(ServerSentEvent.<String>builder().event("thinking").data("正在分析你的问题与对话上下文...").build()),
                Mono.just(ServerSentEvent.<String>builder().event("thinking").data("正在检索情感知识库与私有资料...").build()),
                Mono.fromCallable(() -> prepareLoveTextChat(message, tenantId))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(chat -> Flux.concat(
                                Mono.just(ServerSentEvent.<String>builder().event("thinking").data("已完成资料检索，正在生成分析建议...").build()),
                                loveApp.doChatByStream(message, chatId, chat.context(), retryUserMessageId).map(chunk -> ServerSentEvent.builder(chunk).build()),
                                Mono.fromRunnable(() -> conversationService.saveLatestAssistantRagData(chatId, chat.referencesJson(), chat.traceJson()))
                                        .thenReturn(ServerSentEvent.<String>builder().event("thinking").data("正在整理引用与会话记录...").build()),
                                Mono.just(ServerSentEvent.<String>builder().event("trace").data(chat.traceJson()).build()),
                                Mono.just(ServerSentEvent.<String>builder().event("references").data(chat.referencesJson()).build()),
                                Mono.just(ServerSentEvent.<String>builder("[DONE]").build()))));
    }

    private LoveTextChat prepareLoveTextChat(String message, String tenantId) {
        LoveRagResult ragResult = loveRagService.retrieve(message, chatModelName);
        AgentKnowledgeRagResult privateKnowledge = agentKnowledgeRagService.retrieveWithContext(tenantId, "love", message);
        LoveRagTrace trace = mergePrivateReferences(ragResult.trace(), privateKnowledge);
        try {
            String referencesJson = objectMapper.writeValueAsString(trace.references());
            String traceJson = objectMapper.writeValueAsString(trace);
            return new LoveTextChat(ragResult.context() + "\n" + privateKnowledge.context(), referencesJson, traceJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize knowledge references", exception);
        }
    }

    private LoveRagTrace mergePrivateReferences(LoveRagTrace trace, AgentKnowledgeRagResult privateKnowledge) {
        if (privateKnowledge.references().isEmpty()) return trace;
        List<LoveKnowledgeReference> references = new java.util.ArrayList<>(trace.references());
        references.addAll(privateKnowledge.references());
        return new LoveRagTrace(trace.query(), trace.topK(), trace.similarityThreshold(), trace.candidates(), trace.decision(),
                references, trace.model(), trace.streaming());
    }

    private Flux<ServerSentEvent<String>> doVisionChatWithLoveAppSSE(String message, String chatId, List<String> imageObjectKeys, String tenantId, Long retryUserMessageId) {
        return Flux.concat(
                Mono.just(ServerSentEvent.<String>builder().event("thinking").data("正在理解图片内容...").build()),
                Mono.fromCallable(() -> loveApp.prepareVisionChat(message, chatId, imageObjectKeys, tenantId))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(result -> Flux.concat(
                                Mono.just(ServerSentEvent.<String>builder().event("vision")
                                        .data(toJson(result.analysis())).build()),
                                Mono.just(ServerSentEvent.<String>builder().event("thinking")
                                        .data(result.analysis().available()
                                                ? "已提取图片线索，正在检索相关资料..."
                                                : "图片线索暂不可用，正在继续生成分析...").build()),
                                loveApp.streamVisionChat(message, chatId, imageObjectKeys, result, retryUserMessageId)
                                        .map(chunk -> ServerSentEvent.builder(chunk).build()),
                                toVisionEvents(chatId, result))));
    }

    private Flux<ServerSentEvent<String>> toVisionEvents(String chatId, LoveVisionChatResult result) {
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

    /**
     * SSE 流式调用 AI 恋爱大师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/love_app/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> doChatWithLoveAppServerSentEvent(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }

    /**
     * SSE 流式调用 AI 恋爱大师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/love_app/chat/sse_emitter")
    public SseEmitter doChatWithLoveAppServerSseEmitter(String message, String chatId) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter sseEmitter = new SseEmitter(180000L); // 3 分钟超时
        // 获取 Flux 响应式数据流并且直接通过订阅推送给 SseEmitter
        loveApp.doChatByStream(message, chatId)
                .subscribe(chunk -> {
                    try {
                        sseEmitter.send(chunk);
                    } catch (IOException e) {
                        sseEmitter.completeWithError(e);
                    }
                }, sseEmitter::completeWithError, sseEmitter::complete);
        // 返回
        return sseEmitter;
    }

    /**
     * 流式调用 Manus 超级智能体
     *
     * @param message
     * @return
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message) {
        ZwxManus zwxManus = new ZwxManus(allTools, dashscopeChatModel);
        return zwxManus.runStream(message);
    }

    @GetMapping(value = "/travel-planner/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatWithTravelPlanner(@RequestParam(defaultValue = "default") String tenantId,
                                                                @RequestParam String conversationId, String message,
                                                                @RequestParam(required = false) Long retryUserMessageId) {
        validateTenantId(tenantId);
        String runId = UUID.randomUUID().toString();
        return Flux.create(sink -> {
            java.util.function.Consumer<ExecutionUpdate> progress = update -> {
                int sequence = agentExecutionTraceService.record(runId, tenantId, "travel", conversationId, update.phase(), update.summary(), update.detail());
                sink.next(ServerSentEvent.<String>builder(toJson(Map.of("runId", runId, "sequence", sequence, "phase", update.phase(), "summary", update.summary()))).event("activity").build());
                sink.next(ServerSentEvent.<String>builder(update.summary()).event("thinking").build());
            };
            progress.accept(new ExecutionUpdate("received", "正在接收并理解你的旅行需求...", Map.of("message", message)));
            reactor.core.Disposable subscription = travelPlannerApp.chat(tenantId, conversationId, runId, message, retryUserMessageId, progress,
                    references -> sink.next(ServerSentEvent.<String>builder(toJson(references)).event("references").build())).subscribe(
                    chunk -> sink.next(ServerSentEvent.builder(chunk).build()),
                    sink::error,
                    () -> {
                        sink.next(ServerSentEvent.<String>builder("[DONE]").build());
                        sink.complete();
                    });
            sink.onCancel(() -> {
                subscription.dispose();
                agentExecutionTraceService.deleteRun(tenantId, conversationId, runId);
            });
        });
    }

    @GetMapping("/travel-planner/conversations/{conversationId}/executions/{runId}")
    public List<AgentExecutionEvent> getTravelExecutionEvents(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                                                @PathVariable String conversationId, @PathVariable String runId) {
        validateTenantId(tenantId);
        return agentExecutionTraceService.listTravelEvents(tenantId, conversationId, runId);
    }

    @PostMapping("/travel-planner/conversations")
    public AgentConversationSummary createTravelConversation(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        validateTenantId(tenantId);
        return agentConversationService.createTravelConversation(tenantId);
    }

    @GetMapping("/travel-planner/conversations")
    public List<AgentConversationSummary> listTravelConversations(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        validateTenantId(tenantId);
        return agentConversationService.listTravelConversations(tenantId);
    }

    @GetMapping("/travel-planner/conversations/{conversationId}/messages")
    public List<AgentConversationMessage> getTravelConversationMessages(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                                                          @PathVariable String conversationId) {
        validateTenantId(tenantId);
        return agentConversationService.getTravelMessages(tenantId, conversationId);
    }

    @DeleteMapping("/travel-planner/conversations/{conversationId}/messages/{userMessageId}/assistant")
    public boolean deleteTravelAssistantReply(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                               @PathVariable String conversationId, @PathVariable long userMessageId) {
        validateTenantId(tenantId);
        String runId = agentConversationService.deleteTravelAssistantReply(tenantId, conversationId, userMessageId);
        if (runId != null) agentExecutionTraceService.deleteRun(tenantId, conversationId, runId);
        return runId != null;
    }

    @DeleteMapping("/travel-planner/conversations/{conversationId}")
    public void deleteTravelConversation(@RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                          @PathVariable String conversationId) {
        validateTenantId(tenantId);
        agentConversationService.deleteTravelConversation(tenantId, conversationId);
    }

    private void validateTenantId(String tenantId) {
        if (tenantId == null || !tenantId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid tenant scope");
    }

    private record LoveTextChat(String context, String referencesJson, String traceJson) {
    }
}
