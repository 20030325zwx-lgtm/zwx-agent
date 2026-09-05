package com.zwx.zwxagent.app;

import com.zwx.zwxagent.agent.AgentRegistry;
import com.zwx.zwxagent.conversation.AgentConversationService;
import com.zwx.zwxagent.rag.AgentKnowledgeRagResult;
import com.zwx.zwxagent.rag.AgentKnowledgeRagService;
import com.zwx.zwxagent.rag.LoveKnowledgeReference;
import com.zwx.zwxagent.skills.BuiltInSkillRegistry;
import com.zwx.zwxagent.skills.SkillPromptBuilder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
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
    private final BuiltInSkillRegistry skillRegistry;
    private final SkillPromptBuilder skillPromptBuilder;

    public TestAgentApp(ChatModel dashscopeChatModel, AgentKnowledgeRagService knowledgeRagService,
                        AgentConversationService conversationService, AgentRegistry agentRegistry, BuiltInSkillRegistry skillRegistry,
                        SkillPromptBuilder skillPromptBuilder) {
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
        this.knowledgeRagService = knowledgeRagService;
        this.conversationService = conversationService;
        this.agentRegistry = agentRegistry;
        this.skillRegistry = skillRegistry;
        this.skillPromptBuilder = skillPromptBuilder;
    }

    public Flux<String> chat(String tenantId, String userId, String conversationId, String message, boolean webSearch, Long retryUserMessageId,
                             Consumer<List<LoveKnowledgeReference>> references, String clientRequestId) {
        long userMessageId = retryUserMessageId == null
                ? conversationService.startUserTurn(tenantId, userId, KEY, conversationId, message, clientRequestId)
                : retryUserMessageId;
        AgentKnowledgeRagResult retrieval = knowledgeRagService.retrieveWithContext(tenantId, KEY, message);
        references.accept(retrieval.references());
        String history = conversationService.getRecentMessages(tenantId, userId, KEY, conversationId, 20).stream()
                .map(item -> item.role() + ": " + item.content()).reduce("", (left, right) -> left + "\n" + right);
        StringBuilder answer = new StringBuilder();
        var prompt = chatClient.prompt().system(agentRegistry.get(KEY).systemPrompt() + skillPromptBuilder.build(tenantId, KEY, webSearch) + "\n" + retrieval.context() + "\n最近对话：" + history)
                .user(message);
        var skillTools = skillRegistry.toolCallbacksFor(tenantId, KEY, webSearch);
        if (skillTools.length > 0) prompt.toolCallbacks(skillTools);
        java.util.concurrent.atomic.AtomicBoolean persisted = new java.util.concurrent.atomic.AtomicBoolean(false);
        return prompt.stream().content().doOnNext(answer::append).doFinally(signal -> {
            if (!persisted.compareAndSet(false, true)) return;
            boolean completed = signal == reactor.core.publisher.SignalType.ON_COMPLETE;
            try {
                if (answer.length() == 0) {
                    conversationService.markUserTurnInterrupted(tenantId, userId, userMessageId);
                } else {
                    conversationService.completeUserTurn(tenantId, userId, userMessageId);
                    conversationService.appendAssistantReply(tenantId, userId, KEY, conversationId, userMessageId,
                            answer.toString(), completed ? "COMPLETED" : "INTERRUPTED", null);
                }
            } catch (Exception exception) {
                org.slf4j.LoggerFactory.getLogger(TestAgentApp.class).error("Failed to persist test agent turn", exception);
            }
        });
    }
}
