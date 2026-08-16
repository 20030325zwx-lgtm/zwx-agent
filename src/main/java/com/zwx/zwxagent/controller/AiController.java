package com.zwx.zwxagent.controller;

import com.zwx.zwxagent.agent.ZwxManus;
import com.zwx.zwxagent.app.LoveApp;
import com.zwx.zwxagent.conversation.LoveConversationMessage;
import com.zwx.zwxagent.conversation.LoveConversationService;
import com.zwx.zwxagent.conversation.LoveConversationSummary;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

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
                                                               @RequestParam(required = false) List<String> imageKey) {
        LoveRagTrace trace = loveRagService.trace(message, chatModelName);
        List<LoveKnowledgeReference> references = trace.references();
        String referencesJson;
        String traceJson;
        try {
            referencesJson = objectMapper.writeValueAsString(references);
            traceJson = objectMapper.writeValueAsString(trace);
        } catch (JsonProcessingException e) {
            return Flux.error(new IllegalStateException("Unable to serialize knowledge references", e));
        }
        return Flux.concat(
                loveApp.doChatByStream(message, chatId, imageKey == null ? List.<String>of() : imageKey)
                        .map(chunk -> ServerSentEvent.builder(chunk).build()),
                Mono.just(ServerSentEvent.<String>builder().event("trace").data(traceJson).build()),
                Mono.just(ServerSentEvent.<String>builder().event("references").data(referencesJson).build()));
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
}
