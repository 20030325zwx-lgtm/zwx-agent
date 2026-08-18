package com.zwx.zwxagent.app;

import com.zwx.zwxagent.advisor.MyLoggerAdvisor;
import com.zwx.zwxagent.advisor.ReReadingAdvisor;
import com.zwx.zwxagent.chatmemory.PostgresChatMemory;
import com.zwx.zwxagent.conversation.LoveConversationService;
import com.zwx.zwxagent.rag.LoveAppRagCustomAdvisorFactory;
import com.zwx.zwxagent.rag.QueryRewriter;
import com.zwx.zwxagent.rag.LoveRagResult;
import com.zwx.zwxagent.rag.LoveRagService;
import com.zwx.zwxagent.rag.LoveRagTrace;
import com.zwx.zwxagent.rag.AgentKnowledgeRagService;
import com.zwx.zwxagent.rag.AgentKnowledgeRagResult;
import com.zwx.zwxagent.agent.AgentRegistry;
import com.zwx.zwxagent.skills.BuiltInSkillRegistry;
import com.zwx.zwxagent.skills.SkillPromptBuilder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class LoveApp {

    private final ChatClient chatClient;
    private final ChatClient streamingChatClient;
    private final BuiltInSkillRegistry skillRegistry;
    private final SkillPromptBuilder skillPromptBuilder;

    /**
     * 初始化 ChatClient
     *
     * @param dashscopeChatModel
     */
    private final LoveConversationService conversationService;
    private final LoveVisionChatService loveVisionChatService;
    private final LoveRagService loveRagService;
    private final ObjectMapper objectMapper;
    private final String visionModel;
    private final AgentKnowledgeRagService agentKnowledgeRagService;
    private final AgentRegistry agentRegistry;

    public LoveApp(ChatModel dashscopeChatModel, PostgresChatMemory chatMemory,
                   LoveConversationService conversationService,
                   LoveVisionChatService loveVisionChatService, LoveRagService loveRagService,
                   ObjectMapper objectMapper, @org.springframework.beans.factory.annotation.Value("${app.love.vision-model}") String visionModel,
                   AgentKnowledgeRagService agentKnowledgeRagService, AgentRegistry agentRegistry,
                   BuiltInSkillRegistry skillRegistry, SkillPromptBuilder skillPromptBuilder) {
        this.conversationService = conversationService;
        this.loveVisionChatService = loveVisionChatService;
        this.loveRagService = loveRagService;
        this.objectMapper = objectMapper;
        this.visionModel = visionModel;
        this.agentKnowledgeRagService = agentKnowledgeRagService;
        this.agentRegistry = agentRegistry;
        this.skillRegistry = skillRegistry;
        this.skillPromptBuilder = skillPromptBuilder;
        String systemPrompt = agentRegistry.get("love").systemPrompt();
        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        // 自定义日志 Advisor，可按需开启
                        new MyLoggerAdvisor()
//                        // 自定义推理增强 Advisor，可按需开启
//                       ,new ReReadingAdvisor()
                )
                .build();
        streamingChatClient = ChatClient.builder(dashscopeChatModel).build();
    }

    /**
     * AI 基础对话（支持多轮对话记忆）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChat(String message, String chatId) {
        conversationService.ensureConversation(chatId, message);
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    /**
     * AI 基础对话（支持多轮对话记忆，SSE 流式传输）
     *
     * @param message
     * @param chatId
     * @return
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        return doChatByStream(message, chatId, "");
    }

    public Flux<String> doChatByStream(String message, String chatId, String ragContext) {
        return doChatByStream(message, chatId, ragContext, null);
    }

    public Flux<String> doChatByStream(String message, String chatId, String ragContext, Long retryUserMessageId) {
        return doChatByStream(message, chatId, ragContext, false, retryUserMessageId);
    }

    public Flux<String> doChatByStream(String message, String chatId, String ragContext, boolean webSearch, Long retryUserMessageId) {
        return doChatByStream("default", message, chatId, ragContext, webSearch, retryUserMessageId);
    }

    public Flux<String> doChatByStream(String tenantId, String message, String chatId, String ragContext, boolean webSearch, Long retryUserMessageId) {
        String history = conversationService.getRecentMessages(chatId, 20).stream()
                .map(item -> item.role() + ": " + item.content())
                .collect(Collectors.joining("\n"));
        StringBuilder answer = new StringBuilder();
        var prompt = streamingChatClient
                .prompt()
                .system(agentRegistry.get("love").systemPrompt() + skillPromptBuilder.build(tenantId, "love", webSearch) + "\n\n" + ragContext + "\n\n最近对话：\n" + history)
                .user(message);
        var skillTools = skillRegistry.toolCallbacksFor(tenantId, "love", webSearch);
        if (skillTools.length > 0) prompt.toolCallbacks(skillTools);
        return prompt.stream()
                .content()
                .doOnNext(answer::append)
                .doOnComplete(() -> {
                    if (retryUserMessageId == null) conversationService.saveCompletedTurn(chatId, message, List.of(), answer.toString(), "[]", "{}", null);
                    else conversationService.appendMessage(chatId, "ASSISTANT", answer.toString());
                });
    }

    public LoveVisionChatResult prepareVisionChat(String message, String chatId, List<String> imageObjectKeys, String tenantId) {
        LoveVisionAnalysis analysis = loveVisionChatService.analyze(chatId, message, imageObjectKeys);

        LoveRagResult ragResult = analysis.available()
                ? loveRagService.retrieve(analysis.retrievalQuery(), visionModel)
                : new LoveRagResult(new com.zwx.zwxagent.rag.LoveRagTrace(message, 3, 0.55, List.of(),
                "视觉摘要不可用，未执行知识库检索，模型仅基于图片、系统提示词与会话上下文回答。",
                List.of(), visionModel, true), "");
        AgentKnowledgeRagResult privateKnowledge = agentKnowledgeRagService.retrieveWithContext(tenantId, "love", analysis.retrievalQuery());
        LoveRagTrace trace = mergePrivateReferences(ragResult.trace(), privateKnowledge);
        String scopedContext = privateKnowledge.context();
        String prompt = agentRegistry.get("love").systemPrompt() + "\n\n" + ragResult.context() + "\n" + scopedContext + "\n图片分析仅是待确认线索。回答时明确区分可观察内容与推测，不要把不确定项当作事实。";
        return new LoveVisionChatResult(analysis, trace, prompt);
    }

    public Flux<String> streamVisionChat(String message, String chatId, List<String> imageObjectKeys, LoveVisionChatResult preparation, Long retryUserMessageId) {
        StringBuilder content = new StringBuilder();
        List<com.zwx.zwxagent.conversation.LoveConversationMessage> history = new java.util.ArrayList<>(conversationService.getRecentMessages(chatId, 20));
        history.add(new com.zwx.zwxagent.conversation.LoveConversationMessage(0, "USER", message, imageObjectKeys, List.of(), null, preparation.analysis(), java.time.Instant.now()));
        return loveVisionChatService.streamChat(chatId, history, preparation.systemPrompt())
                .doOnNext(content::append)
                .doOnComplete(() -> {
                    try {
                        String references = objectMapper.writeValueAsString(preparation.ragTrace().references());
                        String trace = objectMapper.writeValueAsString(preparation.ragTrace());
                        if (retryUserMessageId == null) conversationService.saveCompletedTurn(chatId, message, imageObjectKeys, content.toString(), references, trace, objectMapper.writeValueAsString(preparation.analysis()));
                        else { conversationService.appendMessage(chatId, "ASSISTANT", content.toString()); conversationService.saveLatestAssistantRagData(chatId, references, trace); }
                    } catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to serialize vision conversation", exception); }
                });
    }

    record LoveReport(String title, List<String> suggestions) {

    }

    private LoveRagTrace mergePrivateReferences(LoveRagTrace trace, AgentKnowledgeRagResult privateKnowledge) {
        if (privateKnowledge.references().isEmpty()) return trace;
        List<com.zwx.zwxagent.rag.LoveKnowledgeReference> references = new java.util.ArrayList<>(trace.references());
        references.addAll(privateKnowledge.references());
        return new LoveRagTrace(trace.query(), trace.topK(), trace.similarityThreshold(), trace.candidates(), trace.decision(),
                references, trace.model(), trace.streaming());
    }

    /**
     * AI 恋爱报告功能（实战结构化输出）
     *
     * @param message
     * @param chatId
     * @return
     */
    public LoveReport doChatWithReport(String message, String chatId) {
        LoveReport loveReport = chatClient
                .prompt()
                .system(agentRegistry.get("love").systemPrompt() + "每次对话后都要生成恋爱结果，标题为{用户名}的恋爱报告，内容为建议列表")
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .entity(LoveReport.class);
        log.info("loveReport: {}", loveReport);
        return loveReport;
    }

    // AI 恋爱知识库问答功能

    @Resource(name = "loveAppVectorStore")
    private VectorStore loveAppVectorStore;

    @Resource
    private Advisor loveAppRagCloudAdvisor;

    @Resource
    private QueryRewriter queryRewriter;

    /**
     * 和 RAG 知识库进行对话
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithRag(String message, String chatId) {
        // 查询重写
        String rewrittenMessage = queryRewriter.doQueryRewrite(message);
        ChatResponse chatResponse = chatClient
                .prompt()
                // 使用改写后的查询
                .user(rewrittenMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                // 应用 RAG 知识库问答
                .advisors(new QuestionAnswerAdvisor(loveAppVectorStore))
                // 应用 RAG 检索增强服务（基于云知识库服务）
//                .advisors(loveAppRagCloudAdvisor)
                // 应用 RAG 检索增强服务（基于 PgVector 向量存储）
//                .advisors(new QuestionAnswerAdvisor(pgVectorVectorStore))
                // 应用自定义的 RAG 检索增强服务（文档查询器 + 上下文增强器）
//                .advisors(
//                        LoveAppRagCustomAdvisorFactory.createLoveAppRagCustomAdvisor(
//                                loveAppVectorStore, "单身"
//                        )
//                )
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    // AI 调用工具能力
    @Resource
    private ToolCallback[] allTools;

    /**
     * AI 恋爱报告功能（支持调用工具）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithTools(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(allTools)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    // AI 调用 MCP 服务

    @Resource
    private ToolCallbackProvider toolCallbackProvider;

    /**
     * AI 恋爱报告功能（调用 MCP 服务）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithMcp(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }
}
