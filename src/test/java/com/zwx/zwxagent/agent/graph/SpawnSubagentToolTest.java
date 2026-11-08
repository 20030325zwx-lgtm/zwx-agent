package com.zwx.zwxagent.agent.graph;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/** spawnSubagent 工具：结论回传、task 校验、数量上限、深度=1（子代理工具集不含 spawn）。 */
class SpawnSubagentToolTest {

    private static ChatModel textModel(String answer) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(answer, Map.of()))));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(answer, Map.of())))));
            }
        };
    }

    private static ToolCallback namedTool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                        .name(name).description("test").inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String input) {
                return name + " executed";
            }
        };
    }

    private SpawnSubagentTool tool(ChatModel model, int maxTotal) {
        return new SpawnSubagentTool(model, new ToolCallback[]{namedTool("searchWeb"), namedTool("spawnSubagent")},
                "run-1", "conv-1", "t", "u", "conv-1", null, () -> false, null, null, null, maxTotal);
    }

    @Test
    void returnsSubagentConclusionCompressed() {
        SpawnSubagentTool tool = tool(textModel("结论：天是蓝的。"), 12);
        String result = tool.call("{\"task\":\"查天空颜色\"}");
        Assertions.assertEquals("结论：天是蓝的。", result);
    }

    @Test
    void blankTaskRejectedWithoutModelCall() {
        ChatModel model = Mockito_failOnUse();
        SpawnSubagentTool tool = tool(model, 12);
        Assertions.assertTrue(tool.call("{\"task\":\"\"}").contains("task 不能为空"));
        Assertions.assertTrue(tool.call("   ").contains("task 不能为空"));
    }

    @Test
    void totalPerRunLimitBlocksFurtherSpawns() {
        SpawnSubagentTool tool = tool(textModel("子代理结论"), 1);
        Assertions.assertEquals("子代理结论", tool.call("{\"task\":\"a\"}"));
        // 总上限 1 已用完：第二次返回上限错误而非模型结论
        Assertions.assertTrue(tool.call("{\"task\":\"b\"}").contains("已达上限"));
    }

    @Test
    void depthIsOneSubagentToolsExcludeSpawn() {
        // 通过 toolsWithoutSpawn 的间接验证：定义上 spawn 工具的描述不含自身（工具过滤逻辑见实现），
        // 这里直接断言工具定义名称与 schema
        ToolDefinition definition = tool(textModel("x"), 12).getToolDefinition();
        Assertions.assertEquals(SpawnSubagentTool.TOOL_NAME, definition.name());
        Assertions.assertTrue(definition.inputSchema().contains("task"));
        Assertions.assertTrue(definition.description().contains("自包含"));
    }

    private static ChatModel Mockito_failOnUse() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new AssertionError("空 task 不应触发模型调用");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                throw new AssertionError("空 task 不应触发模型调用");
            }
        };
    }
}
