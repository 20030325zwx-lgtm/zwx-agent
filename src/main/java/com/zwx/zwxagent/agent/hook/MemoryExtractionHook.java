package com.zwx.zwxagent.agent.hook;

import com.zwx.zwxagent.memory.MemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 长期记忆提取 hook（afterRun，order=150）：run 结束后把本轮问答异步交给 MemoryService
 * 做 LLM 事实抽取。受 app.agent.memory.extraction-enabled 开关控制（默认关）；
 * 观察型，失败只记日志，绝不影响 agent 主链路。
 */
@Slf4j
@Component
public class MemoryExtractionHook implements AgentHook {

    private final MemoryService memoryService;

    public MemoryExtractionHook(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String id() {
        return "memory-extraction";
    }

    @Override
    public int order() {
        return 150;
    }

    @Override
    public void afterRun(AgentRunContext context) {
        if (isBlank(context.tenantId()) || isBlank(context.agentKey())) return;
        List<Message> history = context.messageHistory();
        if (history == null || history.isEmpty()) return;
        String answer = lastAssistantText(history);
        if (answer == null || answer.isBlank()) return;
        memoryService.extractAsync(context.tenantId(), context.agentKey(), null,
                context.conversationId(), context.userPrompt(), answer);
    }

    private static String lastAssistantText(List<Message> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            Message message = history.get(index);
            if (message instanceof AssistantMessage assistant && assistant.getText() != null && !assistant.getText().isBlank()) {
                return assistant.getText();
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
