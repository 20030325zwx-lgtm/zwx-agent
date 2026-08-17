package com.zwx.zwxagent.app;

import com.zwx.zwxagent.rag.AgentKnowledgeRagService;
import com.zwx.zwxagent.rag.AgentKnowledgeRagResult;
import com.zwx.zwxagent.rag.LoveKnowledgeReference;
import com.zwx.zwxagent.agent.AgentRegistry;
import com.zwx.zwxagent.conversation.AgentConversationService;
import com.zwx.zwxagent.execution.ExecutionUpdate;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;

@Component
public class TravelPlannerApp {

    private final ChatClient chatClient;
    private final ToolCallback[] travelTools;
    private final AgentKnowledgeRagService knowledgeRagService;
    private final AgentConversationService conversationService;
    private final AgentRegistry agentRegistry;

    public TravelPlannerApp(ChatModel dashscopeChatModel, @Qualifier("travelTools") ToolCallback[] travelTools,
                            AgentKnowledgeRagService knowledgeRagService, AgentConversationService conversationService, AgentRegistry agentRegistry) {
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
        this.travelTools = travelTools;
        this.knowledgeRagService = knowledgeRagService;
        this.conversationService = conversationService;
        this.agentRegistry = agentRegistry;
    }

    public Flux<String> chat(String tenantId, String conversationId, String runId, String message, Consumer<ExecutionUpdate> progress) {
        return chat(tenantId, conversationId, runId, message, null, false, progress, references -> {});
    }

    public Flux<String> chat(String tenantId, String conversationId, String runId, String message, Long retryUserMessageId, Consumer<ExecutionUpdate> progress) {
        return chat(tenantId, conversationId, runId, message, retryUserMessageId, false, progress, references -> {});
    }

    public Flux<String> chat(String tenantId, String conversationId, String runId, String message, Long retryUserMessageId,
                             boolean webSearch, Consumer<ExecutionUpdate> progress, Consumer<List<LoveKnowledgeReference>> referenceConsumer) {
        progress.accept(new ExecutionUpdate("analysis", "正在分析旅行需求与对话上下文...", java.util.Map.of("message", message)));
        progress.accept(new ExecutionUpdate("retrieval", "正在检索当前智能体的私有资料...", java.util.Map.of("query", message)));
        AgentKnowledgeRagResult retrieval = knowledgeRagService.retrieveWithContext(tenantId, "travel", message);
        String context = retrieval.context();
        referenceConsumer.accept(retrieval.references());
        progress.accept(new ExecutionUpdate("retrieval", context.isBlank() ? "未命中私有资料，正在准备联网规划..." : "已召回私有资料，正在制定规划...", java.util.Map.of("contextAvailable", !context.isBlank())));
        String history = conversationService.getRecentTravelMessages(tenantId, conversationId, 20).stream()
                .map(item -> item.role() + ": " + item.content()).reduce("", (left, right) -> left + "\n" + right);
        StringBuilder answer = new StringBuilder();
        var prompt = chatClient.prompt().system(agentRegistry.get("travel").systemPrompt() + (webSearch ? "" : "\n本轮未开启联网搜索，不得调用外部工具。") + "\n" + context + "\n最近对话：" + history)
                .user(message);
        if (webSearch) prompt.toolCallbacks(reportingTools(progress));
        return prompt.stream().content()
                .doOnSubscribe(subscription -> progress.accept(new ExecutionUpdate("generation", "正在生成旅行方案...", java.util.Map.of("historyMessageCount", conversationService.getRecentTravelMessages(tenantId, conversationId, 20).size()))))
                .doOnNext(answer::append)
                .doOnComplete(() -> {
                    if (retryUserMessageId == null) conversationService.saveCompletedTravelTurn(tenantId, conversationId, message, answer.toString(), runId);
                    else conversationService.appendTravelMessage(tenantId, conversationId, "ASSISTANT", answer.toString(), runId);
                    progress.accept(new ExecutionUpdate("persistence", "方案已生成并保存到历史会话。", java.util.Map.of("answerLength", answer.length())));
                });
    }

    private ToolCallback[] reportingTools(Consumer<ExecutionUpdate> progress) {
        return java.util.Arrays.stream(travelTools).map(tool -> new ToolCallback() {
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
