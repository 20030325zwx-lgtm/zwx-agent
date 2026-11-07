package com.zwx.zwxagent.agent.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 流式 think 路径：stub ChatModel 下发原始 delta 分块，验证
 * 文本增量实时回调、工具调用聚合、消息历史与阻塞路径行为一致、重试标记下发。
 */
class ToolCallAgentStreamThinkTest {

    private static ChatResponse chunk(String text, AssistantMessage.ToolCall... calls) {
        AssistantMessage message = new AssistantMessage(text == null ? "" : text, Map.of(), List.of(calls));
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ToolCallAgent newAgent(ChatModel model, StringBuilder sinkSeen) {
        ToolCallAgent agent = new ToolCallAgent(new org.springframework.ai.tool.ToolCallback[0]);
        agent.setName("测试");
        agent.setSystemPrompt("你是测试智能体");
        agent.setChatClient(org.springframework.ai.chat.client.ChatClient.builder(model).build());
        agent.setThinkTextSink(sinkSeen == null ? null : sinkSeen::append);
        // think() 的停止检查依赖运行态；直调 think() 需模拟 run() 已进入 RUNNING
        agent.setState(com.zwx.zwxagent.agent.model.AgentState.RUNNING);
        return agent;
    }

    @Test
    void streamingThinkEmitsDeltasAndAggregatesToolCalls() {
        // 两轮 delta：一轮纯文本 + 工具调用分片（首块带 id/name，续块空 id 补参数）
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) { throw new AssertionError("流式路径不应走阻塞调用"); }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(
                        chunk("我先查一下。"),
                        chunk("", new AssistantMessage.ToolCall("t1", "function", "web", "{\"q\":")),
                        chunk("", new AssistantMessage.ToolCall("", "", "", "\"猫\"}")));
            }
        };
        StringBuilder seen = new StringBuilder();
        ToolCallAgent agent = newAgent(model, seen);
        agent.getMessageList().add(new org.springframework.ai.chat.messages.UserMessage("查一下猫"));

        boolean needTool = agent.think();

        Assertions.assertEquals("我先查一下。", seen.toString());
        Assertions.assertTrue(needTool);
        Assertions.assertTrue(agent.isLastStepUsedTool());
        Assertions.assertFalse(agent.isLastThinkFailed());
        AssistantMessage output = agent.getToolCallChatResponse().getResult().getOutput();
        Assertions.assertEquals(1, output.getToolCalls().size());
        Assertions.assertEquals("web", output.getToolCalls().get(0).name());
        Assertions.assertEquals("{\"q\":\"猫\"}", output.getToolCalls().get(0).arguments());
        // 有工具调用时不写助手消息（与阻塞路径一致，act 会成对写入）
        Assertions.assertEquals(1, agent.getMessageList().size());
    }

    @Test
    void streamingThinkWithoutToolCallRecordsAssistantMessage() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) { throw new AssertionError("流式路径不应走阻塞调用"); }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(chunk("你好"), chunk("呀")); }
        };
        StringBuilder seen = new StringBuilder();
        ToolCallAgent agent = newAgent(model, seen);
        agent.getMessageList().add(new org.springframework.ai.chat.messages.UserMessage("打个招呼"));

        boolean needTool = agent.think();

        Assertions.assertFalse(needTool);
        Assertions.assertEquals("你好呀", seen.toString());
        Assertions.assertEquals(2, agent.getMessageList().size());
        Assertions.assertEquals("你好呀",
                agent.getMessageList().get(agent.getMessageList().size() - 1).getText());
    }

    @Test
    void brokenStreamRetriesWithHonestMarkerAndSucceeds() {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) { throw new AssertionError("流式路径不应走阻塞调用"); }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                if (attempts.incrementAndGet() == 1) {
                    return Flux.error(new RuntimeException("连接中断"));
                }
                return Flux.just(chunk("重试成功"));
            }
        };
        StringBuilder seen = new StringBuilder();
        ToolCallAgent agent = newAgent(model, seen);
        agent.getMessageList().add(new org.springframework.ai.chat.messages.UserMessage("hi"));

        // 第一次流失败 → 1s 后重试（第 2 次成功），期间向 sink 下发诚实标记
        boolean needTool = agent.think();
        Assertions.assertFalse(needTool);
        Assertions.assertTrue(seen.toString().contains("正在重试"));
        Assertions.assertTrue(seen.toString().contains("重试成功"));
        Assertions.assertFalse(agent.isLastThinkFailed());
    }

    @Test
    void noSinkFallsBackToBlockingCall() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return chunk("阻塞路径回答");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) { throw new AssertionError("无 sink 不应走流式"); }
        };
        ToolCallAgent agent = newAgent(model, null);
        agent.getMessageList().add(new org.springframework.ai.chat.messages.UserMessage("hi"));

        boolean needTool = agent.think();
        Assertions.assertFalse(needTool);
        Assertions.assertEquals("阻塞路径回答",
                agent.getMessageList().get(agent.getMessageList().size() - 1).getText());
    }
}
