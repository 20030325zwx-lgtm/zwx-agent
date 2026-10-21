package com.zwx.zwxagent.app;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationMessage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemBase;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemImage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemText;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.configuration.AppConfigurationService;
import com.zwx.zwxagent.configuration.DynamicChatModelFactory;
import com.zwx.zwxagent.configuration.ResolvedModelConfig;
import com.zwx.zwxagent.conversation.LoveConversationMessage;
import com.zwx.zwxagent.storage.LoveImageStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class LoveVisionChatService {

    private final AppConfigurationService configurationService;
    private final DynamicChatModelFactory modelFactory;
    private final LoveImageStorageService imageStorageService;
    private final ObjectMapper objectMapper;

    public LoveVisionChatService(AppConfigurationService configurationService,
                                 DynamicChatModelFactory modelFactory,
                                 LoveImageStorageService imageStorageService,
                                 ObjectMapper objectMapper) {
        this.configurationService = configurationService;
        this.modelFactory = modelFactory;
        this.imageStorageService = imageStorageService;
        this.objectMapper = objectMapper;
    }

    public LoveVisionAnalysis analyze(String userId, String conversationId, String userMessage, List<String> imageObjectKeys) {
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
            ResolvedModelConfig config = resolveConfig(userId);
            List<String> imageUrls = imageUrls(conversationId, imageObjectKeys);
            String raw = isDashScope(config)
                    ? dashScopeCall(config, List.of(dashScopeMessage("system", analysisPrompt, List.of()),
                            dashScopeMessage("user", userMessage, imageUrls)))
                    : springAiCall(config, analysisPrompt, userMessage, imageUrls);
            return objectMapper.readValue(stripCodeFence(raw), LoveVisionAnalysis.class).normalized();
        } catch (Exception exception) {
            log.warn("Vision analysis unavailable for conversation {}: {}", conversationId, exception.getMessage());
            return LoveVisionAnalysis.unavailable(userMessage);
        }
    }

    public String chat(String userId, String conversationId, List<LoveConversationMessage> history, String systemPrompt) {
        return streamChat(userId, conversationId, history, systemPrompt)
                .collectList().map(items -> String.join("", items)).block();
    }

    public Flux<String> streamChat(String userId, String conversationId, List<LoveConversationMessage> history, String systemPrompt) {
        ResolvedModelConfig config = resolveConfig(userId);
        if (isDashScope(config)) {
            List<Object> messages = new ArrayList<>();
            messages.add(dashScopeMessage("system", systemPrompt, List.of()));
            history.forEach(item -> messages.add(dashScopeMessage(toDashScopeRole(item.role()), item.content(),
                    imageObjectKeys(conversationId, item.imageObjectKeys()))));
            return dashScopeStream(config, messages);
        }
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        history.forEach(item -> messages.add(toSpringAiMessage(conversationId, item)));
        return springAiStream(config, messages);
    }

    private boolean isDashScope(ResolvedModelConfig config) {
        return !AppConfigurationService.PROVIDER_OPENAI_COMPATIBLE.equalsIgnoreCase(config.provider());
    }

    private ResolvedModelConfig resolveConfig(String userId) {
        return configurationService.resolve(userId, AppConfigurationService.VISION);
    }

    private String dashScopeCall(ResolvedModelConfig config, List<Object> messages) throws Exception {
        MultiModalConversationResult result = dashScopeClient(config).call(MultiModalConversationParam.builder()
                .apiKey(config.apiKey())
                .model(config.modelName())
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

    private Flux<String> dashScopeStream(ResolvedModelConfig config, List<Object> messages) {
        return Flux.defer(() -> {
            try {
                MultiModalConversationParam parameters = MultiModalConversationParam.builder()
                        .apiKey(config.apiKey())
                        .model(config.modelName())
                        .messages(messages)
                        .incrementalOutput(true)
                        .build();
                return Flux.from(dashScopeClient(config).streamCall(parameters))
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

    private MultiModalConversation dashScopeClient(ResolvedModelConfig config) {
        String baseUrl = config.baseUrl();
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.matches("https?://dashscope\\.aliyuncs\\.com/?")
                || baseUrl.matches("https?://dashscope-intl\\.aliyuncs\\.com/?")) {
            return new MultiModalConversation();
        }
        String normalized = baseUrl.replaceAll("/+$", "");
        if (!normalized.endsWith("/api/v1")) normalized = normalized + "/api/v1";
        return new MultiModalConversation("http", normalized);
    }

    private String springAiCall(ResolvedModelConfig config, String systemPrompt, String userMessage, List<String> imageUrls) {
        return ChatClient.builder(modelFactory.model(config))
                .build()
                .prompt()
                .messages(new SystemMessage(systemPrompt), springAiUserMessage(userMessage, imageUrls))
                .call()
                .content();
    }

    private Flux<String> springAiStream(ResolvedModelConfig config, List<Message> messages) {
        return ChatClient.builder(modelFactory.model(config))
                .build()
                .prompt()
                .messages(messages)
                .stream()
                .content();
    }

    private Message toSpringAiMessage(String conversationId, LoveConversationMessage item) {
        if ("ASSISTANT".equals(item.role())) {
            return new AssistantMessage(item.content() == null ? "" : item.content());
        }
        return springAiUserMessage(item.content(), imageObjectKeys(conversationId, item.imageObjectKeys()));
    }

    private UserMessage springAiUserMessage(String text, List<String> imageUrls) {
        List<Media> media = imageUrls.stream()
                .map(url -> new Media(new MimeType("image", "jpeg"), URI.create(url)))
                .toList();
        return UserMessage.builder()
                .text(text == null ? "" : text)
                .media(media)
                .build();
    }

    private List<String> imageObjectKeys(String conversationId, List<String> imageObjectKeys) {
        if (imageObjectKeys == null || imageObjectKeys.isEmpty()) return List.of();
        return imageObjectKeys.stream().map(key -> imageStorageService.presignedReadUrl(conversationId, key)).toList();
    }

    private List<String> imageUrls(String conversationId, List<String> imageObjectKeys) {
        return imageObjectKeys(conversationId, imageObjectKeys);
    }

    private MultiModalConversationMessage dashScopeMessage(String role, String text, List<String> imageUrls) {
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

    private String stripCodeFence(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLineEnd >= 0 && lastFence > firstLineEnd) return trimmed.substring(firstLineEnd + 1, lastFence).trim();
        }
        return trimmed;
    }
}
