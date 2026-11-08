package com.zwx.zwxagent.capability.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

/** 并行工具执行：多调用聚合保持原顺序、单调用直调、异常包装为错误结果而非中断。 */
class ParallelizingToolCallingManagerTest {

    private static ToolCallback slowTool(String name, long sleepMs, String result) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name(name).description("test").inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String input) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return result;
            }
        };
    }

    private static ToolCallback failingTool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name(name).description("test").inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String input) {
                throw new IllegalStateException("boom");
            }
        };
    }

    private static ChatResponse multiCallResponse(AssistantMessage.ToolCall... calls) {
        AssistantMessage assistant = new AssistantMessage("", Map.of(), List.of(calls));
        return new ChatResponse(List.of(new Generation(assistant)));
    }

    private static Prompt promptWithTools(ToolCallback[] tools) {
        var options = ToolCallingChatOptions.builder()
                .toolCallbacks(tools)
                .internalToolExecutionEnabled(false)
                .build();
        return new Prompt(List.of(new UserMessage("hi")), options);
    }

    @Test
    void parallelExecutionPreservesCallOrderAndAggregatesResults() {
        var manager = new ParallelizingToolCallingManager();
        // 每个工具 sleep 300ms：串行 ≈900ms，并行 ≈300ms
        ChatResponse response = multiCallResponse(
                new AssistantMessage.ToolCall("1", "function", "toolA", "{}"),
                new AssistantMessage.ToolCall("2", "function", "toolB", "{}"),
                new AssistantMessage.ToolCall("3", "function", "toolC", "{}"));
        ToolCallback[] tools = {slowTool("toolA", 300, "A 结果"), slowTool("toolB", 300, "B 结果"),
                slowTool("toolC", 300, "C 结果")};
        Prompt prompt = promptWithTools(tools);

        long startedAt = System.currentTimeMillis();
        ToolExecutionResult result = manager.executeToolCalls(prompt, response);
        long elapsed = System.currentTimeMillis() - startedAt;

        ToolResponseMessage last = (ToolResponseMessage) result.conversationHistory()
                .get(result.conversationHistory().size() - 1);
        Assertions.assertEquals(3, last.getResponses().size());
        Assertions.assertEquals("toolA", last.getResponses().get(0).name());
        Assertions.assertEquals("A 结果", last.getResponses().get(0).responseData());
        Assertions.assertEquals("toolB", last.getResponses().get(1).name());
        Assertions.assertEquals("toolC", last.getResponses().get(2).name());
        Assertions.assertTrue(elapsed < 850, "并行执行应显著快于串行（实测 " + elapsed + "ms）");
    }

    @Test
    void singleCallFallsThroughToDelegate() {
        var manager = new ParallelizingToolCallingManager();
        ChatResponse response = multiCallResponse(new AssistantMessage.ToolCall("1", "function", "toolA", "{}"));
        Prompt prompt = promptWithTools(new ToolCallback[]{slowTool("toolA", 0, "A 结果")});
        ToolExecutionResult result = manager.executeToolCalls(prompt, response);
        ToolResponseMessage last = (ToolResponseMessage) result.conversationHistory()
                .get(result.conversationHistory().size() - 1);
        Assertions.assertEquals("toolA", last.getResponses().get(0).name());
    }

    @Test
    void toolFailureBecomesErrorMessageInsteadOfBreakingBatch() {
        var manager = new ParallelizingToolCallingManager();
        ChatResponse response = multiCallResponse(
                new AssistantMessage.ToolCall("1", "function", "badTool", "{}"),
                new AssistantMessage.ToolCall("2", "function", "goodTool", "{}"));
        Prompt prompt = promptWithTools(new ToolCallback[]{failingTool("badTool"),
                slowTool("goodTool", 0, "goodTool ok")});
        ToolExecutionResult result = manager.executeToolCalls(prompt, response);
        ToolResponseMessage last = (ToolResponseMessage) result.conversationHistory()
                .get(result.conversationHistory().size() - 1);
        Assertions.assertEquals(2, last.getResponses().size());
        Assertions.assertTrue(last.getResponses().get(0).responseData().contains("boom"));
        Assertions.assertTrue(last.getResponses().get(1).responseData().contains("goodTool"));
    }
}
