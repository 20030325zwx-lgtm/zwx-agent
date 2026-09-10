package com.zwx.zwxagent.agent.hook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工作记忆压缩 hook（beforeThink，order=250，转换型）：
 * messageList 总字符超预算时，把最旧的消息块（保留最近 recent-keep 条）用 LLM 压成一条摘要
 * UserMessage 原位替换；LLM 失败降级为直接丢弃最旧块。块边界自动避开
 * "助手工具调用 + 工具响应" 配对，保证压缩后消息序列对模型仍然合法。
 * system prompt 由框架单独传、不在压缩范围。
 */
@Slf4j
@Component
public class MemoryCompressionHook implements AgentHook {

    static final String SUMMARY_PREFIX = "【历史对话摘要】\n";
    static final String SUMMARY_INSTRUCTION = "把以下对话历史压缩成一条摘要，保留关键事实、用户要求与已得出的结论，500 字以内，直接输出摘要正文。";

    private final ChatClient chatClient;
    private final boolean enabled;
    private final int contextBudget;
    private final int recentKeep;
    private static final int MAX_BLOCK_CHARS = 12000;

    public MemoryCompressionHook(ChatModel dashscopeChatModel,
                                 @Value("${app.agent.memory.compression-enabled:true}") boolean enabled,
                                 @Value("${app.agent.memory.context-budget:24000}") int contextBudget,
                                 @Value("${app.agent.memory.recent-keep:6}") int recentKeep) {
        this.chatClient = dashscopeChatModel == null ? null : ChatClient.builder(dashscopeChatModel).build();
        this.enabled = enabled;
        this.contextBudget = Math.max(1000, contextBudget);
        this.recentKeep = Math.max(2, recentKeep);
    }

    @Override
    public String id() {
        return "memory-compression";
    }

    @Override
    public int order() {
        return 250;
    }

    @Override
    public void beforeThink(AgentRunContext context) {
        List<Message> history = context.messageHistory();
        if (!enabled || history == null || history.size() <= recentKeep) return;
        if (totalChars(history) <= contextBudget) return;
        compress(history);
    }

    /** 压缩最旧块：成功替换为摘要消息，失败丢弃该块；最近 recent-keep 条始终原样保留。 */
    void compress(List<Message> history) {
        int blockEnd = adjustBlockEnd(history, history.size() - recentKeep);
        if (blockEnd <= 0) return;
        String blockText = serialize(history.subList(0, blockEnd));
        String summary = null;
        try {
            summary = summarize(blockText);
        } catch (Exception cause) {
            log.warn("[memory] 工作记忆压缩 LLM 失败，降级为丢弃最旧块：{}", cause.getMessage());
        }
        history.subList(0, blockEnd).clear();
        if (summary != null && !summary.isBlank()) {
            history.add(0, new UserMessage(SUMMARY_PREFIX + truncate(summary, 800)));
        }
        log.info("[memory] 工作记忆压缩完成：块大小 {} 条，摘要 {}",
                blockEnd, summary == null ? "不可用（已丢弃）" : "已注入");
    }

    /** 让块边界不拆散"助手工具调用 + 工具响应"配对：被保留首条不能是孤立工具响应，块尾不能是带工具调用的助手消息。 */
    static int adjustBlockEnd(List<Message> history, int initialBlockEnd) {
        int blockEnd = Math.min(Math.max(0, initialBlockEnd), history.size());
        while (blockEnd > 0 && blockEnd < history.size()) {
            Message keptFirst = history.get(blockEnd);
            Message blockLast = history.get(blockEnd - 1);
            boolean splitPair = keptFirst instanceof ToolResponseMessage
                    || (blockLast instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty());
            if (!splitPair) break;
            blockEnd++;
        }
        if (blockEnd >= history.size()) return 0;
        return blockEnd;
    }

    static int totalChars(List<Message> history) {
        int total = 0;
        for (Message message : history) {
            String text = message.getText();
            total += text == null ? 0 : text.length();
        }
        return total;
    }

    static String serialize(List<Message> block) {
        StringBuilder text = new StringBuilder();
        for (Message message : block) {
            String label;
            if (message instanceof UserMessage) label = "用户";
            else if (message instanceof AssistantMessage) label = "助手";
            else if (message instanceof ToolResponseMessage toolResponse) {
                label = null;
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    if (text.length() >= MAX_BLOCK_CHARS) break;
                    text.append("工具 ").append(response.name()).append(" 返回：")
                            .append(truncate(response.responseData(), 1500)).append('\n');
                }
                if (text.length() >= MAX_BLOCK_CHARS) break;
                continue;
            }
            else if (message instanceof SystemMessage) label = "系统";
            else label = "消息";
            if (text.length() >= MAX_BLOCK_CHARS) break;
            String content = message.getText();
            text.append(label).append("：").append(truncate(content == null ? "" : content, 2000)).append('\n');
        }
        return text.toString();
    }

    /** 可覆写以便单测注入固定摘要 / 模拟失败。 */
    String summarize(String blockText) {
        if (chatClient == null) throw new IllegalStateException("ChatModel 不可用");
        return chatClient.prompt()
                .system(SUMMARY_INSTRUCTION)
                .user(blockText)
                .call()
                .content();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
