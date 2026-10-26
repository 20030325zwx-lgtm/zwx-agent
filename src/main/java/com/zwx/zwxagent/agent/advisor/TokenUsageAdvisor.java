package com.zwx.zwxagent.agent.advisor;

import com.zwx.zwxagent.agent.observability.TokenUsageService;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Supplier;

/**
 * token 计量切面：记录每次模型调用的真实 token 与提示词构成（系统提示 / 工具 schema / 消息体）。
 * 只做观测，任何异常都不得影响生成，故整体吞掉异常。
 */
public class TokenUsageAdvisor implements CallAdvisor, StreamAdvisor {

    private final String phase;
    private final TokenUsageService tokenUsage;
    private final Supplier<String> runIdSupplier;

    public TokenUsageAdvisor(String phase, TokenUsageService tokenUsage, Supplier<String> runIdSupplier) {
        this.phase = phase;
        this.tokenUsage = tokenUsage;
        this.runIdSupplier = runIdSupplier;
    }

    @Override
    public String getName() {
        return "TokenUsageAdvisor:" + phase;
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        observe(request, response);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Flux<ChatClientResponse> responses = chain.nextStream(request);
        return new ChatClientMessageAggregator().aggregateChatClientResponse(responses, aggregated -> observe(request, aggregated));
    }

    private void observe(ChatClientRequest request, ChatClientResponse response) {
        if (tokenUsage == null) return;
        try {
            ChatResponse chatResponse = response == null ? null : response.chatResponse();
            if (chatResponse == null || chatResponse.getResults().isEmpty()) return;
            Usage usage = chatResponse.getMetadata().getUsage();
            List<Message> messages = request.prompt().getInstructions();
            int systemChars = 0;
            int messageChars = 0;
            int messageCount = 0;
            int toolResultChars = 0;
            for (Message message : messages) {
                int length = message.getText() == null ? 0 : message.getText().length();
                if (message.getMessageType() == MessageType.SYSTEM) {
                    systemChars += length;
                } else {
                    messageChars += length;
                    messageCount++;
                }
                if (message instanceof AssistantMessage assistant) {
                    for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                        toolResultChars += length(call.name()) + length(call.arguments());
                    }
                }
                if (message instanceof ToolResponseMessage toolResponse) {
                    for (ToolResponseMessage.ToolResponse item : toolResponse.getResponses()) {
                        toolResultChars += length(item.name()) + length(item.responseData());
                    }
                }
            }
            int toolSchemaChars = 0;
            int toolCount = 0;
            if (request.prompt().getOptions() instanceof ToolCallingChatOptions toolOptions) {
                for (ToolCallback callback : toolOptions.getToolCallbacks()) {
                    ToolDefinition definition = callback.getToolDefinition();
                    toolSchemaChars += length(definition.name())
                            + length(definition.description())
                            + length(definition.inputSchema());
                    toolCount++;
                }
            }
            tokenUsage.record(runIdSupplier.get(), phase, new TokenUsageService.CallSample(
                    usage == null ? 0 : nullToZero(usage.getPromptTokens()),
                    usage == null ? 0 : nullToZero(usage.getCompletionTokens()),
                    systemChars, toolSchemaChars, toolCount, messageChars, messageCount, toolResultChars));
        } catch (Exception exception) {
            // 计量失败不影响生成
        }
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
