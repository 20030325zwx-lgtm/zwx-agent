package com.zwx.zwxagent.agent.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
class ThinkStreamAggregatorTest {

    private static ChatResponse chunk(String text, AssistantMessage.ToolCall... calls) {
        AssistantMessage message = new AssistantMessage(text == null ? "" : text, Map.of(), List.of(calls));
        return new ChatResponse(List.of(new Generation(message)));
    }

    @Test
    void textDeltasStreamToSinkAndConcatenate() {
        StringBuilder seen = new StringBuilder();
        ChatResponse aggregated = ThinkStreamAggregator.collect(
                Flux.just(chunk("你"), chunk("好"), chunk("，世界")), seen::append, () -> false);
        Assertions.assertEquals("你好，世界", aggregated.getResult().getOutput().getText());
        Assertions.assertEquals("你好，世界", seen.toString());
        Assertions.assertTrue(aggregated.getResult().getOutput().getToolCalls().isEmpty());
    }

    @Test
    void toolCallFragmentsMergeByIdThenBlankIdContinuation() {
        ChatResponse aggregated = ThinkStreamAggregator.collect(Flux.just(
                chunk("", new AssistantMessage.ToolCall("a1", "function", "web", "{\"q\":")),
                chunk("", new AssistantMessage.ToolCall("", "", "", "\"aster\"}")),
                chunk("", new AssistantMessage.ToolCall("a2", "function", "terminal", "{\"c\"")),
                chunk("", new AssistantMessage.ToolCall("", "", "", ":\"ls\"}"))),
                delta -> { }, () -> false);
        List<AssistantMessage.ToolCall> calls = aggregated.getResult().getOutput().getToolCalls();
        Assertions.assertEquals(2, calls.size());
        Assertions.assertEquals("a1", calls.get(0).id());
        Assertions.assertEquals("web", calls.get(0).name());
        Assertions.assertEquals("{\"q\":\"aster\"}", calls.get(0).arguments());
        Assertions.assertEquals("a2", calls.get(1).id());
        Assertions.assertEquals("terminal", calls.get(1).name());
        Assertions.assertEquals("{\"c\":\"ls\"}", calls.get(1).arguments());
    }

    @Test
    void sameIdRepeatAppendsArguments() {
        ChatResponse aggregated = ThinkStreamAggregator.collect(Flux.just(
                chunk("", new AssistantMessage.ToolCall("x", "function", "file", "{\"p\"")),
                chunk("", new AssistantMessage.ToolCall("x", "", "", ":1}"))),
                delta -> { }, () -> false);
        List<AssistantMessage.ToolCall> calls = aggregated.getResult().getOutput().getToolCalls();
        Assertions.assertEquals(1, calls.size());
        Assertions.assertEquals("{\"p\":1}", calls.get(0).arguments());
    }

    @Test
    void usageFromLastChunkIsCarried() {
        ChatResponse withUsage = new ChatResponse(List.of(new Generation(new AssistantMessage("答案", Map.of()))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5, 15)).build());
        ChatResponse aggregated = ThinkStreamAggregator.collect(Flux.just(chunk("答"), chunk("案"), withUsage),
                delta -> { }, () -> false);
        Assertions.assertNotNull(aggregated.getMetadata().getUsage());
        Assertions.assertEquals(15, aggregated.getMetadata().getUsage().getTotalTokens());
    }

    @Test
    void fullyEmptyStreamThrowsForRetryPath() {
        Assertions.assertThrows(IllegalStateException.class,
                () -> ThinkStreamAggregator.collect(Flux.empty(), delta -> { }, () -> false));
    }

    @Test
    void stopCheckAbortsAndReturnsPartialAggregate() {
        // 首块处理后翻转停止开关：后续块被丢弃，返回已收到的部分聚合
        StringBuilder seen = new StringBuilder();
        ChatResponse aggregated = ThinkStreamAggregator.collect(
                Flux.fromIterable(List.of(chunk("部分"), chunk("内容"), chunk("永远不到达"))),
                seen::append, () -> seen.length() > 0);
        Assertions.assertEquals("部分", aggregated.getResult().getOutput().getText());
        Assertions.assertEquals("部分", seen.toString());
    }

    @Test
    void blankIdWithNoPriorCallIsIgnoredWithoutFailure() {
        ChatResponse aggregated = ThinkStreamAggregator.collect(
                Flux.just(chunk("", new AssistantMessage.ToolCall("", "", "", "\"孤\""))),
                delta -> { }, () -> false);
        Assertions.assertTrue(aggregated.getResult().getOutput().getToolCalls().isEmpty());
    }
}
