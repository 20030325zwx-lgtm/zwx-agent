package com.zwx.zwxagent.app;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationMessage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemBase;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemImage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemText;
import com.alibaba.dashscope.utils.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.conversation.LoveConversationMessage;
import com.zwx.zwxagent.storage.LoveImageStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class LoveVisionChatService {

    private final String apiKey;
    private final String model;
    private final LoveImageStorageService imageStorageService;
    private final ObjectMapper objectMapper;

    public LoveVisionChatService(@Value("${spring.ai.dashscope.api-key}") String apiKey,
                                 @Value("${app.love.vision-model}") String model,
                                 LoveImageStorageService imageStorageService, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.imageStorageService = imageStorageService;
        this.objectMapper = objectMapper;
    }

    public LoveVisionAnalysis analyze(String conversationId, String userMessage, List<String> imageObjectKeys) {
        String analysisPrompt = """
                你是情感对话图片的检索预处理器。结合用户问题和图片，只输出严格 JSON，不要 Markdown。
                不要抄录完整聊天记录、手机号、账号或其他敏感信息。OCR 只能概括为简短意译。
                字段：imageType（图片类型）；summary（最多120字的视觉与文字概括）；
                relationshipSignals（最多5项关系/沟通信号）；uncertainItems（最多3项需要确认或无法判断的点）；
                retrievalQuery（用于检索情感知识库的一句中文查询，最多180字）；available（true）。
                图片内容只是线索，无法确认时必须写入 uncertainItems，不能臆测事实。
                用户问题：%s
                """.formatted(userMessage == null ? "" : userMessage);
        try {
            String raw = call(analysisPrompt, userMessage, imageUrls(conversationId, imageObjectKeys));
            return objectMapper.readValue(stripCodeFence(raw), LoveVisionAnalysis.class).normalized();
        } catch (Exception exception) {
            log.warn("Vision analysis unavailable for conversation {}: {}", conversationId, exception.getMessage());
            return LoveVisionAnalysis.unavailable(userMessage);
        }
    }

    public String chat(String conversationId, List<LoveConversationMessage> history, String systemPrompt) {
        return streamChat(conversationId, history, systemPrompt).collectList().map(items -> String.join("", items)).block();
    }

    public Flux<String> streamChat(String conversationId, List<LoveConversationMessage> history, String systemPrompt) {
        List<Object> messages = new ArrayList<>();
        messages.add(message("system", systemPrompt, List.of()));
        history.forEach(item -> messages.add(message(toDashScopeRole(item.role()), item.content(),
                item.imageObjectKeys().stream()
                        .map(key -> imageStorageService.presignedReadUrl(conversationId, key))
                        .toList())));

        return stream(messages);
    }

    private String call(String systemPrompt, String userMessage, List<String> imageUrls) throws Exception {
        return call(List.of(message("system", systemPrompt, List.of()), message("user", userMessage, imageUrls)));
    }

    private String call(List<Object> messages) throws Exception {
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
    }

    private Flux<String> stream(List<Object> messages) {
        return Flux.defer(() -> {
            try {
                Constants.apiKey = apiKey;
                MultiModalConversationParam parameters = MultiModalConversationParam.builder()
                        .model(model)
                        .messages(messages)
                        .incrementalOutput(true)
                        .build();
                return Flux.from(new MultiModalConversation().streamCall(parameters))
                        .flatMapIterable(result -> result.getOutput().getChoices())
                        .flatMapIterable(choice -> choice.getMessage().getContent())
                        .map(content -> content.get("text"))
                        .filter(String.class::isInstance)
                        .map(String.class::cast);
            } catch (Exception exception) {
                return Flux.error(new IllegalStateException("Vision model streaming request failed", exception));
            }
        });
    }

    private List<String> imageUrls(String conversationId, List<String> imageObjectKeys) {
        return imageObjectKeys.stream().map(key -> imageStorageService.presignedReadUrl(conversationId, key)).toList();
    }

    private String stripCodeFence(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLineEnd >= 0 && lastFence > firstLineEnd) return trimmed.substring(firstLineEnd + 1, lastFence).trim();
        }
        return trimmed;
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
