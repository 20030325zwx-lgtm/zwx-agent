package com.zwx.zwxagent.app;

import com.zwx.zwxagent.rag.AgentKnowledgeRagService;
import com.zwx.zwxagent.rag.AgentKnowledgeRagResult;
import com.zwx.zwxagent.rag.LoveKnowledgeReference;
import com.zwx.zwxagent.agent.AgentRegistry;
import com.zwx.zwxagent.conversation.AgentConversationService;
import com.zwx.zwxagent.execution.ExecutionUpdate;
import com.zwx.zwxagent.skills.BuiltInSkillRegistry;
import com.zwx.zwxagent.skills.SkillPromptBuilder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;

@Component
public class TravelPlannerApp {

    private final ChatClient chatClient;
    private final BuiltInSkillRegistry skillRegistry;
    private final SkillPromptBuilder skillPromptBuilder;
    private final AgentKnowledgeRagService knowledgeRagService;
    private final AgentConversationService conversationService;
    private final AgentRegistry agentRegistry;

    public TravelPlannerApp(ChatModel dashscopeChatModel, BuiltInSkillRegistry skillRegistry, SkillPromptBuilder skillPromptBuilder,
                            AgentKnowledgeRagService knowledgeRagService, AgentConversationService conversationService, AgentRegistry agentRegistry) {
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
        this.skillRegistry = skillRegistry;
        this.skillPromptBuilder = skillPromptBuilder;
        this.knowledgeRagService = knowledgeRagService;
        this.conversationService = conversationService;
        this.agentRegistry = agentRegistry;
    }

    public Flux<String> chat(String tenantId, String userId, String conversationId, String runId, String message, Consumer<ExecutionUpdate> progress) {
        return chat(tenantId, userId, conversationId, runId, message, null, false, progress, references -> {}, null);
    }

    public Flux<String> chat(String tenantId, String userId, String conversationId, String runId, String message, Long retryUserMessageId, Consumer<ExecutionUpdate> progress) {
        return chat(tenantId, userId, conversationId, runId, message, retryUserMessageId, false, progress, references -> {}, null);
    }

    public Flux<String> chat(String tenantId, String userId, String conversationId, String runId, String message, Long retryUserMessageId,
                             boolean webSearch, Consumer<ExecutionUpdate> progress, Consumer<List<LoveKnowledgeReference>> referenceConsumer, String clientRequestId) {
        long userMessageId = retryUserMessageId == null
                ? conversationService.startUserTurn(tenantId, userId, "travel", conversationId, message, clientRequestId)
                : retryUserMessageId;
        progress.accept(new ExecutionUpdate("analysis", "正在分析旅行需求与对话上下文...", java.util.Map.of("message", message)));
        progress.accept(new ExecutionUpdate("retrieval", "正在检索当前智能体的私有资料...", java.util.Map.of("query", message)));
        AgentKnowledgeRagResult retrieval = knowledgeRagService.retrieveWithContext(tenantId, "travel", message);
        String context = retrieval.context();
        referenceConsumer.accept(retrieval.references());
        progress.accept(new ExecutionUpdate("retrieval", context.isBlank() ? "未命中私有资料，正在准备联网规划..." : "已召回私有资料，正在制定规划...", java.util.Map.of("contextAvailable", !context.isBlank())));
        String history = conversationService.getRecentTravelMessages(tenantId, userId, conversationId, 20).stream()
                .map(item -> item.role() + ": " + item.content()).reduce("", (left, right) -> left + "\n" + right);
        history = history.length() > 8000 ? "...（较早的对话已省略）\n" + history.substring(history.length() - 8000) : history;
        StringBuilder answer = new StringBuilder();
        var prompt = chatClient.prompt().system(agentRegistry.get("travel").systemPrompt() + skillPromptBuilder.build(tenantId, "travel", webSearch) + "\n" + context + "\n最近对话：" + history)
                .user(message);
        var skillTools = skillRegistry.toolCallbacksFor(tenantId, "travel", webSearch);
        if (skillTools.length > 0) prompt.toolCallbacks(reportingTools(skillTools, progress));
        java.util.concurrent.atomic.AtomicBoolean persisted = new java.util.concurrent.atomic.AtomicBoolean(false);
        return prompt.stream().content()
                .doOnSubscribe(subscription -> progress.accept(new ExecutionUpdate("generation", "正在生成旅行方案...", java.util.Map.of("historyMessageCount", conversationService.getRecentTravelMessages(tenantId, userId, conversationId, 20).size()))))
                .doOnNext(answer::append)
                .doFinally(signal -> {
                    if (!persisted.compareAndSet(false, true)) return;
                    boolean completed = signal == reactor.core.publisher.SignalType.ON_COMPLETE;
                    try {
                        if (answer.length() == 0) {
                            conversationService.markUserTurnInterrupted(tenantId, userId, userMessageId);
                        } else {
                            conversationService.completeUserTurn(tenantId, userId, userMessageId);
                            conversationService.appendAssistantReply(tenantId, userId, "travel", conversationId, userMessageId,
                                    answer.toString(), completed ? "COMPLETED" : "INTERRUPTED", runId);
                        }
                    } catch (Exception exception) {
                        org.slf4j.LoggerFactory.getLogger(TravelPlannerApp.class).error("Failed to persist travel turn", exception);
                    }
                });
    }

    private ToolCallback[] reportingTools(ToolCallback[] tools, Consumer<ExecutionUpdate> progress) {
        return java.util.Arrays.stream(tools).map(tool -> new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return tool.getToolDefinition();
            }

            @Override
            public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
                return tool.getToolMetadata();
            }

            @Override
            public String call(String input) {
                progress.accept(new ExecutionUpdate("tool-call", "正在调用联网搜索工具...", java.util.Map.of("tool", tool.getToolDefinition().name(), "arguments", input)));
                String result = tool.call(input);
                progress.accept(toolFailed(result)
                        ? new ExecutionUpdate("tool-failed", "联网搜索未成功，正在基于已知信息标注不确定项...", java.util.Map.of("tool", tool.getToolDefinition().name(), "error", truncate(result)))
                        : new ExecutionUpdate("tool-result", "已获得联网搜索结果，正在整理行程建议...", java.util.Map.of("tool", tool.getToolDefinition().name(), "result", truncate(result))));
                return result;
            }

            @Override
            public String call(String input, ToolContext toolContext) {
                progress.accept(new ExecutionUpdate("tool-call", "正在调用联网搜索工具...", java.util.Map.of("tool", tool.getToolDefinition().name(), "arguments", input)));
                String result = tool.call(input, toolContext);
                progress.accept(toolFailed(result)
                        ? new ExecutionUpdate("tool-failed", "联网搜索未成功，正在基于已知信息标注不确定项...", java.util.Map.of("tool", tool.getToolDefinition().name(), "error", truncate(result)))
                        : new ExecutionUpdate("tool-result", "已获得联网搜索结果，正在整理行程建议...", java.util.Map.of("tool", tool.getToolDefinition().name(), "result", truncate(result))));
                return result;
            }
        }).toArray(ToolCallback[]::new);
    }

    private String truncate(String value) { return value == null ? "" : value.substring(0, Math.min(6000, value.length())); }
    private boolean toolFailed(String value) { return value == null || value.startsWith("SEARCH_UNAVAILABLE:"); }
}
