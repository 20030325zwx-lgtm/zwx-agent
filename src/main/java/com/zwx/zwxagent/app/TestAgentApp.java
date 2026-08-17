package com.zwx.zwxagent.app;

import com.zwx.zwxagent.agent.AgentRegistry;
import com.zwx.zwxagent.conversation.AgentConversationService;
import com.zwx.zwxagent.rag.AgentKnowledgeRagResult;
import com.zwx.zwxagent.rag.AgentKnowledgeRagService;
import com.zwx.zwxagent.rag.LoveKnowledgeReference;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;

@Component
public class TestAgentApp {
    private static final String KEY = "test";
    private static final String DEFAULT_TITLE = "新的功能测试";
    private final ChatClient chatClient;
    private final AgentKnowledgeRagService knowledgeRagService;
    private final AgentConversationService conversationService;
    private final AgentRegistry agentRegistry;
    private final ToolCallback[] travelTools;

    public TestAgentApp(ChatModel dashscopeChatModel, AgentKnowledgeRagService knowledgeRagService,
                        AgentConversationService conversationService, AgentRegistry agentRegistry, @Qualifier("travelTools") ToolCallback[] travelTools) {
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
        this.knowledgeRagService = knowledgeRagService;
        this.conversationService = conversationService;
        this.agentRegistry = agentRegistry;
        this.travelTools = travelTools;
    }

    public Flux<String> chat(String tenantId, String conversationId, String message, boolean webSearch, Long retryUserMessageId,
                             Consumer<List<LoveKnowledgeReference>> references) {
        AgentKnowledgeRagResult retrieval = knowledgeRagService.retrieveWithContext(tenantId, KEY, message);
        references.accept(retrieval.references());
        String history = conversationService.getRecentMessages(tenantId, KEY, conversationId, 20).stream()
                .map(item -> item.role() + ": " + item.content()).reduce("", (left, right) -> left + "\n" + right);
        StringBuilder answer = new StringBuilder();
        var prompt = chatClient.prompt().system(agentRegistry.get(KEY).systemPrompt() + (webSearch ? "" : "\n本轮未开启联网搜索，不得调用外部工具。") + "\n" + retrieval.context() + "\n最近对话：" + history)
                .user(message);
        if (webSearch) prompt.toolCallbacks(travelTools);
        return prompt.stream().content().doOnNext(answer::append).doOnComplete(() -> {
                    if (retryUserMessageId == null) conversationService.saveCompletedTurn(tenantId, KEY, conversationId, DEFAULT_TITLE, message, answer.toString());
                    else conversationService.appendMessage(tenantId, KEY, conversationId, "ASSISTANT", answer.toString());
                });
    }
}
