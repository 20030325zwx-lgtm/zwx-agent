package com.zwx.zwxagent.chatmemory;

import com.zwx.zwxagent.conversation.LoveConversationMessage;
import com.zwx.zwxagent.conversation.LoveConversationService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PostgresChatMemory implements ChatMemory {

    private static final int MEMORY_WINDOW_SIZE = 20;

    private final LoveConversationService conversationService;

    public PostgresChatMemory(LoveConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        messages.forEach(message -> conversationService.appendMessage(
                conversationId,
                message.getMessageType().name(),
                message.getText()));
    }

    @Override
    public List<Message> get(String conversationId) {
        return conversationService.getRecentMessages(conversationId, MEMORY_WINDOW_SIZE)
                .stream()
                .map(this::toMessage)
                .toList();
    }

    @Override
    public void clear(String conversationId) {
        org.slf4j.LoggerFactory.getLogger(PostgresChatMemory.class)
                .warn("ChatMemory.clear is not supported without an authenticated actor; conversation {} was left untouched", conversationId);
    }

    private Message toMessage(LoveConversationMessage message) {
        return switch (message.role()) {
            case "ASSISTANT" -> new AssistantMessage(message.content());
            case "SYSTEM" -> new SystemMessage(message.content());
            default -> new UserMessage(message.content());
        };
    }
}
