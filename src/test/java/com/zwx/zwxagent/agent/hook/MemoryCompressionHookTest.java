package com.zwx.zwxagent.agent.hook;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryCompressionHookTest {

    private MemoryCompressionHook hook(String summary) {
        return new MemoryCompressionHook(null, true, 24000, 6) {
            @Override
            String summarize(String blockText) {
                if (summary == null) throw new IllegalStateException("llm down");
                return summary;
            }
        };
    }

    private static List<Message> bulkyHistory(int count) {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(new UserMessage("消息" + i + "：" + "x".repeat(4000)));
        }
        return history;
    }

    @Test
    void overBudgetBlockIsReplacedBySummaryAndRecentKept() {
        List<Message> history = bulkyHistory(10);
        int before = MemoryCompressionHook.totalChars(history);
        hook("压缩后的摘要").compress(history);
        assertEquals(7, history.size());
        assertUserMessageWithText(history.getFirst(), MemoryCompressionHook.SUMMARY_PREFIX + "压缩后的摘要");
        for (int i = 1; i < 7; i++) {
            assertTrue(((UserMessage) history.get(i)).getText().startsWith("消息" + (i + 3) + "："), "最近 6 条应原样保留且顺序不变");
        }
        assertTrue(MemoryCompressionHook.totalChars(history) < before, "压缩后总字符应减少");
    }

    @Test
    void llmFailureDegradesToDroppingOldestBlock() {
        List<Message> history = bulkyHistory(10);
        hook(null).compress(history);
        assertEquals(6, history.size());
        assertTrue(((UserMessage) history.getFirst()).getText().startsWith("消息4："), "失败降级为丢弃最旧块");
    }

    @Test
    void underBudgetHistoryIsUntouched() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("短消息"), new UserMessage("短消息2")));
        MemoryCompressionHook hook = new MemoryCompressionHook(null, true, 24000, 6) {
            @Override
            String summarize(String blockText) {
                throw new AssertionError("预算内不应触发压缩");
            }
        };
        hook.beforeThink(AgentRunContext.builder().messageHistory(history).build());
        assertEquals(2, history.size());
    }

    @Test
    void smallHistoryIsNeverTouched() {
        List<Message> history = new ArrayList<>(bulkyHistory(5));
        hook("摘要").compress(history);
        assertEquals(5, history.size(), "不超过 recent-keep 时无可压缩块");
    }

    @Test
    void blockBoundaryNeverSplitsToolCallPair() {
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("x".repeat(4000)));
        history.add(new UserMessage("x".repeat(4000)));
        history.add(new UserMessage("x".repeat(4000)));
        history.add(new AssistantMessage("调用工具", Map.of(),
                List.of(new AssistantMessage.ToolCall("t1", "function", "webSearch", "{}"))));
        history.add(new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("t1", "webSearch", "结果")), Map.of()));
        for (int i = 0; i < 5; i++) history.add(new UserMessage("x".repeat(4000)));
        // 初始块边界 4（保留最近 6 条）恰好落在 助手工具调用 与 工具响应 之间
        assertEquals(5, MemoryCompressionHook.adjustBlockEnd(history, 4), "边界应后移把配对完整划入被压缩块");
        hook("摘要").compress(history);
        assertEquals(6, history.size());
        assertTrue(history.stream().noneMatch(message -> message instanceof ToolResponseMessage), "配对必须整体存在或整体移除");
        assertTrue(history.stream().noneMatch(message -> message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()));
        assertTrue(MemoryCompressionHook.totalChars(history) <= 24000);
    }

    private static void assertUserMessageWithText(Message message, String expected) {
        assertTrue(message instanceof UserMessage);
        assertEquals(expected, ((UserMessage) message).getText());
    }
}
