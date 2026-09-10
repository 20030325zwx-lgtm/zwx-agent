package com.zwx.zwxagent.agent.hook;

import com.zwx.zwxagent.memory.MemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryInjectionHookTest {

    private MemoryInjectionHook hookWithFacts(List<String> facts) {
        MemoryService stub = new MemoryService(null, null, false) {
            @Override
            public List<String> factsFor(String tenantId, String agentKey, String userPrompt, int limit) {
                return facts;
            }
        };
        return new MemoryInjectionHook(stub);
    }

    private AgentRunContext context(List<Message> history) {
        return AgentRunContext.builder()
                .tenantId("default")
                .agentKey("super")
                .userPrompt("帮我规划周末")
                .messageHistory(history)
                .build();
    }

    @Test
    void factsArePrefixedIntoFirstUserMessage() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("帮我规划周末"), new AssistantMessage("好的")));
        hookWithFacts(List.of("用户在上海", "用户喜欢日料")).beforeRun(context(history));
        String text = ((UserMessage) history.getFirst()).getText();
        assertTrue(text.contains("【长期记忆参考】"));
        assertTrue(text.contains("- 用户在上海"));
        assertTrue(text.contains("- 用户喜欢日料"));
        assertTrue(text.endsWith("【本次请求】\n帮我规划周末"));
        assertEquals("好的", ((AssistantMessage) history.get(1)).getText(), "其余消息不受影响");
    }

    @Test
    void noFactsLeavesHistoryUntouched() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("帮我规划周末")));
        hookWithFacts(List.of()).beforeRun(context(history));
        assertEquals("帮我规划周末", ((UserMessage) history.getFirst()).getText());
    }

    @Test
    void nonUserFirstMessageIsSkipped() {
        List<Message> history = new ArrayList<>(List.of(new AssistantMessage("奇怪的首条消息")));
        hookWithFacts(List.of("用户在上海")).beforeRun(context(history));
        assertEquals("奇怪的首条消息", ((AssistantMessage) history.getFirst()).getText());
    }

    @Test
    void missingIdentityIsSkipped() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("帮我规划周末")));
        AgentRunContext noIdentity = AgentRunContext.builder().messageHistory(history).build();
        hookWithFacts(List.of("用户在上海")).beforeRun(noIdentity);
        assertEquals("帮我规划周末", ((UserMessage) history.getFirst()).getText());
    }

    @Test
    void nullHistoryIsSkipped() {
        hookWithFacts(List.of("用户在上海")).beforeRun(AgentRunContext.builder().build());
        // 未抛异常即通过
    }
}
