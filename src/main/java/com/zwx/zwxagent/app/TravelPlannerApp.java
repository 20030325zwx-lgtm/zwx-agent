package com.zwx.zwxagent.app;

import com.zwx.zwxagent.rag.AgentKnowledgeRagService;
import com.zwx.zwxagent.conversation.AgentConversationService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class TravelPlannerApp {

    private static final String SYSTEM_PROMPT = """
            你是旅行规划专家。先确认出发地、目的地、日期、人数、预算和偏好，再给出可执行的行程建议。
            涉及天气、地图位置、营业时间、交通或实时信息时，优先调用联网搜索工具，不要编造实时数据。
            工具结果不足时明确说明不确定性，并给出用户可以验证的关键词或步骤。
            """;

    private final ChatClient chatClient;
    private final ToolCallback[] travelTools;
    private final AgentKnowledgeRagService knowledgeRagService;
    private final AgentConversationService conversationService;

    public TravelPlannerApp(ChatModel dashscopeChatModel, @Qualifier("travelTools") ToolCallback[] travelTools,
                            AgentKnowledgeRagService knowledgeRagService, AgentConversationService conversationService) {
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
        this.travelTools = travelTools;
        this.knowledgeRagService = knowledgeRagService;
        this.conversationService = conversationService;
    }

    public Flux<String> chat(String tenantId, String conversationId, String message) {
        conversationService.ensureTravelConversation(tenantId, conversationId, message);
        conversationService.appendTravelMessage(tenantId, conversationId, "USER", message);
        String context = knowledgeRagService.context(tenantId, "travel", message);
        String history = conversationService.getRecentTravelMessages(tenantId, conversationId, 20).stream()
                .map(item -> item.role() + ": " + item.content()).reduce("", (left, right) -> left + "\n" + right);
        StringBuilder answer = new StringBuilder();
        return chatClient.prompt().system(SYSTEM_PROMPT + "\n" + context + "\n最近对话：" + history)
                .user(message).toolCallbacks(travelTools).stream().content()
                .doOnNext(answer::append)
                .doOnComplete(() -> conversationService.appendTravelMessage(tenantId, conversationId, "ASSISTANT", answer.toString()));
    }
}
