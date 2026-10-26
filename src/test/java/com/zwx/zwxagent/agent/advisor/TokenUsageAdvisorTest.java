package com.zwx.zwxagent.agent.advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.agent.observability.TokenUsageService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TokenUsageAdvisorTest {

    private final TokenUsageService tokenUsage = new TokenUsageService(new ObjectMapper());
    private final AtomicReference<String> runId = new AtomicReference<>("run-1");

    private static ToolCallback tool(String name, String description, String schema) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String arguments) {
                return "ok";
            }
        };
    }

    private static ChatClientResponse response(int promptTokens, int completionTokens) {
        return new ChatClientResponse(new ChatResponse(
                List.of(new Generation(new AssistantMessage("answer"))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(promptTokens, completionTokens))
                        .build()), Map.of());
    }

    private ChatClientResponse advise(ChatClientRequest request, ChatClientResponse response) {
        CallAdvisorChain chain = new CallAdvisorChain() {
            @Override
            public ChatClientResponse nextCall(ChatClientRequest ignored) {
                return response;
            }

            @Override
            public List<org.springframework.ai.chat.client.advisor.api.CallAdvisor> getCallAdvisors() {
                return List.of();
            }
        };
        return new TokenUsageAdvisor("worker·研究员#1", tokenUsage, runId::get).adviseCall(request, chain);
    }

    private static ChatClientRequest request(List<Message> messages, ToolCallingChatOptions options) {
        return new ChatClientRequest(options == null ? new Prompt(messages) : new Prompt(messages, options), Map.of());
    }

    @Test
    void separatesSystemPromptFromMessagesAndCountsToolSchema() throws Exception {
        String system = "S".repeat(1000);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(tool("web_search", "搜索互联网".repeat(10), "{\"type\":\"object\"}".repeat(20)))
                .build();
        ChatClientRequest chatRequest = request(
                List.of(new SystemMessage(system),
                        new UserMessage("用户问题".repeat(50)),
                        new UserMessage("工具结果".repeat(50))),
                options);

        ChatClientResponse upstream = response(4200, 180);
        ChatClientResponse returned = advise(chatRequest, upstream);

        assertSame(upstream, returned);
        assertEquals(4200, tokenUsage.promptTokens("run-1"));
        Map<String, Object> phase = firstPhase();
        assertEquals("worker·研究员#1", phase.get("phase"));
        assertEquals(1000, phase.get("avgSystemChars"));
        assertEquals(400, phase.get("avgMessageChars"));
        assertEquals(2, ((Number) phase.get("avgMessageCount")).intValue());
        assertEquals(1, phase.get("maxToolCount"));
        int expectedSchema = "web_search".length() + "搜索互联网".length() * 10 + "{\"type\":\"object\"}".length() * 20;
        assertEquals(expectedSchema, phase.get("avgToolSchemaChars"));
    }

    @Test
    void countsToolCallArgumentsAndToolResponsesHiddenFromMessageText() throws Exception {
        String payload = "搜索结果正文".repeat(200);
        AssistantMessage withCalls = new AssistantMessage("", Map.of(),
                List.of(new AssistantMessage.ToolCall("call-1", "function", "searchWeb", "{\"query\":\"valkey\"}")));
        ToolResponseMessage toolResult = new ToolResponseMessage(
                List.of(new ToolResponseMessage.ToolResponse("call-1", "searchWeb", payload)));

        advise(request(List.of(new SystemMessage("系统"), withCalls, toolResult), null), response(9000, 60));

        Map<String, Object> phase = firstPhase();
        int expected = "searchWeb".length() + "{\"query\":\"valkey\"}".length()
                + "searchWeb".length() + payload.length();
        assertEquals(expected, phase.get("avgToolResultChars"));
        assertEquals(expected, phase.get("maxToolResultChars"));
        assertEquals(2, ((Number) phase.get("avgMessageCount")).intValue());
    }

    @Test
    void recordsZeroToolSchemaWhenOptionsCarryNoTools() throws Exception {
        advise(request(List.of(new UserMessage("hello")), null), response(100, 20));

        Map<String, Object> phase = firstPhase();
        assertEquals(0, phase.get("avgToolSchemaChars"));
        assertEquals(0, phase.get("maxToolCount"));
        assertEquals(100, tokenUsage.promptTokens("run-1"));
    }

    @Test
    void skipsEmptyChatResponseWithoutFailingTheCall() {
        ChatClientResponse empty = new ChatClientResponse(
                new ChatResponse(List.of(), ChatResponseMetadata.builder().build()), Map.of());

        assertNotNull(advise(request(List.of(new UserMessage("hello")), null), empty));
        assertEquals(0, tokenUsage.calls("run-1"));
    }

    @Test
    void toleratesAdvisorWithoutRunId() {
        runId.set("");
        advise(request(List.of(new UserMessage("hello")), null), response(100, 20));

        assertEquals(0, tokenUsage.calls(""));
        assertEquals(0, tokenUsage.calls("run-1"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstPhase() throws Exception {
        Map<String, Object> root = new ObjectMapper().readValue(tokenUsage.usageJson("run-1"), Map.class);
        return ((List<Map<String, Object>>) root.get("phases")).get(0);
    }
}
