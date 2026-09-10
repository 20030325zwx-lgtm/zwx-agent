package com.zwx.zwxagent.agent.hook;

import com.zwx.zwxagent.memory.MemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 长期记忆注入 hook（beforeRun，order=50）：把跨会话事实前置进 run 首条用户消息，
 * 让模型带着用户历史偏好理解本次请求。无事实 / 无身份 / 无工作记忆时零改动。
 */
@Slf4j
@Component
public class MemoryInjectionHook implements AgentHook {

    static final String FACTS_PREFIX_TEMPLATE = "【长期记忆参考】以下是此用户此前交互中积累的事实，供理解上下文，不要直接复述：\n%s\n\n【本次请求】\n";

    private final MemoryService memoryService;

    public MemoryInjectionHook(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String id() {
        return "memory-injection";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public void beforeRun(AgentRunContext context) {
        List<Message> history = context.messageHistory();
        if (history == null || history.isEmpty()) return;
        if (isBlank(context.tenantId()) || isBlank(context.agentKey())) return;
        List<String> facts = memoryService.factsFor(context.tenantId(), context.agentKey(), context.userPrompt(), 5);
        if (facts.isEmpty()) return;
        if (!(history.getFirst() instanceof UserMessage first)) return;
        StringBuilder prefix = new StringBuilder(FACTS_PREFIX_TEMPLATE.formatted(
                String.join("\n", facts.stream().map(fact -> "- " + fact).toList())));
        history.set(0, new UserMessage(prefix + first.getText()));
        log.info("[memory] 注入 {} 条长期事实到 run 首条消息（agent={}）", facts.size(), context.agentKey());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
