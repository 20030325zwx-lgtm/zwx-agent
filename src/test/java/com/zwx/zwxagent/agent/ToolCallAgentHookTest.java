package com.zwx.zwxagent.agent;

import com.zwx.zwxagent.agent.hook.PlannedToolCall;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class ToolCallAgentHookTest {

    @Test
    void unchangedPlanReturnsOriginalResponse() {
        ChatResponse response = chatResponse(
                new AssistantMessage.ToolCall("1", "function", "web", "{}"),
                new AssistantMessage.ToolCall("2", "function", "terminal", "{\"cmd\":\"ls\"}"));
        List<PlannedToolCall> planned = ToolCallAgent.plannedCalls(response);
        Assertions.assertSame(response, ToolCallAgent.applyPlannedCalls(response, planned));
    }

    @Test
    void modifiedPlanRebuildsResponse() {
        ChatResponse response = chatResponse(
                new AssistantMessage.ToolCall("1", "function", "web", "{}"),
                new AssistantMessage.ToolCall("2", "function", "terminal", "{\"cmd\":\"rm -rf /\"}"));
        List<PlannedToolCall> planned = new ArrayList<>(ToolCallAgent.plannedCalls(response));
        planned.removeIf(call -> call.name().equals("terminal"));
        planned.get(0).arguments("{\"safe\":true}");

        ChatResponse rebuilt = ToolCallAgent.applyPlannedCalls(response, planned);
        Assertions.assertNotSame(response, rebuilt);
        AssistantMessage output = rebuilt.getResult().getOutput();
        Assertions.assertEquals(1, output.getToolCalls().size());
        Assertions.assertEquals("web", output.getToolCalls().get(0).name());
        Assertions.assertEquals("{\"safe\":true}", output.getToolCalls().get(0).arguments());
        Assertions.assertEquals("模型文本", output.getText());
    }

    @Test
    void modifiedExecutionsRebuildToolResponseMessage() {
        ToolResponseMessage original = new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("1", "web", "原始结果内容"),
                new ToolResponseMessage.ToolResponse("2", "terminal", "长结果".repeat(1000))));
        List<BaseAgent.ToolExecution> executions = List.of(
                new BaseAgent.ToolExecution("web", "{}", "原始结果内容"),
                new BaseAgent.ToolExecution("terminal", "{}", "已截断的结果"));

        ToolResponseMessage rebuilt = ToolCallAgent.rebuildToolResponseMessage(original, executions);
        Assertions.assertEquals(2, rebuilt.getResponses().size());
        Assertions.assertEquals("原始结果内容", rebuilt.getResponses().get(0).responseData());
        Assertions.assertEquals("已截断的结果", rebuilt.getResponses().get(1).responseData());
        Assertions.assertEquals("1", rebuilt.getResponses().get(0).id());
        // 内容完全未变时也返回新实例（调用方仅在确有变化时才走到重建），这里校验响应内容不受影响
        ToolResponseMessage untouched = ToolCallAgent.rebuildToolResponseMessage(original,
                List.of(new BaseAgent.ToolExecution("web", "{}", "原始结果内容"),
                        new BaseAgent.ToolExecution("terminal", "{}", "长结果".repeat(1000))));
        Assertions.assertEquals(original.getResponses(), untouched.getResponses());
    }

    private ChatResponse chatResponse(AssistantMessage.ToolCall... toolCalls) {
        AssistantMessage message = new AssistantMessage("模型文本", Map.of(), List.of(toolCalls));
        return new ChatResponse(List.of(new Generation(message)));
    }
}
