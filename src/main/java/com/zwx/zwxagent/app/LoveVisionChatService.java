package com.zwx.zwxagent.app;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationMessage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemBase;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemImage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemText;
import com.alibaba.dashscope.utils.Constants;
import com.zwx.zwxagent.conversation.LoveConversationMessage;
import com.zwx.zwxagent.storage.LoveImageStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class LoveVisionChatService {

    private final String apiKey;
    private final String model;
    private final LoveImageStorageService imageStorageService;

    public LoveVisionChatService(@Value("${spring.ai.dashscope.api-key}") String apiKey,
                                 @Value("${app.love.vision-model}") String model,
                                 LoveImageStorageService imageStorageService) {
        this.apiKey = apiKey;
        this.model = model;
        this.imageStorageService = imageStorageService;
    }

    public String chat(String conversationId, List<LoveConversationMessage> history, String systemPrompt) {
        List<Object> messages = new ArrayList<>();
        messages.add(message("system", systemPrompt, List.of()));
        history.forEach(item -> messages.add(message(toDashScopeRole(item.role()), item.content(),
                item.imageObjectKeys().stream()
                        .map(key -> imageStorageService.presignedReadUrl(conversationId, key))
                        .toList())));

        try {
            Constants.apiKey = apiKey;
            MultiModalConversationResult result = new MultiModalConversation().call(MultiModalConversationParam.builder()
                    .model(model)
                    .messages(messages)
                    .build());
            return result.getOutput().getChoices().stream()
                    .flatMap(choice -> choice.getMessage().getContent().stream())
                    .map(content -> content.get("text"))
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Vision model returned no text"));
        } catch (Exception e) {
            throw new IllegalStateException("Vision model request failed", e);
        }
    }

    private MultiModalConversationMessage message(String role, String text, List<String> imageUrls) {
        List<MultiModalMessageItemBase> content = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            content.add(new MultiModalMessageItemText(text));
        }
        imageUrls.forEach(url -> content.add(new MultiModalMessageItemImage(url)));
        return MultiModalConversationMessage.builder().role(role).content(content).build();
    }

    private String toDashScopeRole(String role) {
        return "ASSISTANT".equals(role) ? "assistant" : "user";
    }
}
