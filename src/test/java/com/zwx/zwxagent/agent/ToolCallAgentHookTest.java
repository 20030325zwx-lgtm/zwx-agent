package com.zwx.zwxagent.agent;

import com.zwx.zwxagent.agent.hook.PlannedToolCall;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
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

    private ChatResponse chatResponse(AssistantMessage.ToolCall... toolCalls) {
        AssistantMessage message = new AssistantMessage("模型文本", Map.of(), List.of(toolCalls));
        return new ChatResponse(List.of(new Generation(message)));
    }
}
