package com.zwx.zwxagent.agent;

import com.zwx.zwxagent.advisor.MyLoggerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 鱼皮的 AI 超级智能体（拥有自主规划能力，可以直接使用）
 */
@Component
public class ZwxManus extends ToolCallAgent {

    public ZwxManus(ToolCallback[] allTools, ChatModel dashscopeChatModel) {
        super(allTools);
        this.setName("zwxManus");
        String SYSTEM_PROMPT = """
                You are ZwxManus, an all-capable AI assistant, aimed at solving any task presented by the user.
                You have various tools at your disposal that you can call upon to efficiently complete complex requests.
                When the task is complete, do not call another tool. Return a concise Chinese delivery summary instead:
                first state the outcome, then summarize the work completed in this turn, list important results or limitations,
                and tell the user when a generated file is available below. Never claim a tool action succeeded unless its result confirms it.
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);
        String NEXT_STEP_PROMPT = """
                Based on user needs, proactively select the most appropriate tool or combination of tools.
                For complex tasks, you can break down the problem and use different tools step by step to solve it.
                Continue only while another tool call is needed to make progress. When enough information or output has been obtained,
                respond without tool calls using the required delivery summary.
                """;
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(20);
        // 初始化 AI 对话客户端
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }

    public void restoreHistory(List<com.zwx.zwxagent.conversation.AgentConversationMessage> history) {
        history.forEach(message -> {
            if ("USER".equals(message.role())) getMessageList().add(new UserMessage(message.content()));
            else if ("ASSISTANT".equals(message.role())) getMessageList().add(new AssistantMessage(message.content()));
        });
    }
}
