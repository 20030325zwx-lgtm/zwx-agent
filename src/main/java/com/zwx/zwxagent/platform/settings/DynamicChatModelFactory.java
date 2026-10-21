package com.zwx.zwxagent.platform.settings;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按生效配置构建并缓存 ChatModel。配置变更后由 AppConfigurationService 调用 clear()。
 */
@Component
public class DynamicChatModelFactory {

    private static final int MAX_CACHE_SIZE = 64;
    private static final String DEFAULT_DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com";
    private final ConcurrentHashMap<String, ChatModel> cache = new ConcurrentHashMap<>();

    public ChatModel model(ResolvedModelConfig config) {
        validate(config);
        String cacheKey = String.join("|", config.provider(), nullToEmpty(config.baseUrl()),
                config.modelName(), Integer.toHexString(config.apiKey().hashCode()));
        if (cache.size() >= MAX_CACHE_SIZE) cache.clear();
        return cache.computeIfAbsent(cacheKey, ignored -> build(config));
    }

    public void clear() {
        cache.clear();
    }

    private ChatModel build(ResolvedModelConfig config) {
        if (AppConfigurationService.PROVIDER_OPENAI_COMPATIBLE.equalsIgnoreCase(config.provider())) {
            OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                    .baseUrl(config.baseUrl())
                    .apiKey(config.apiKey());
            String normalizedBase = config.baseUrl().replaceAll("/+$", "");
            if (normalizedBase.endsWith("/v1")) {
                apiBuilder.completionsPath("/chat/completions");
            }
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(config.modelName())
                    .build();
            return OpenAiChatModel.builder()
                    .openAiApi(apiBuilder.build())
                    .defaultOptions(options)
                    .build();
        }
        String baseUrl = config.baseUrl() == null || config.baseUrl().isBlank()
                ? DEFAULT_DASHSCOPE_BASE_URL : config.baseUrl();
        DashScopeApi api = DashScopeApi.builder()
                .baseUrl(baseUrl)
                .apiKey(config.apiKey())
                .build();
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .withModel(config.modelName())
                .build();
        return DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(options)
                .build();
    }

    private void validate(ResolvedModelConfig config) {
        if (config == null) throw new IllegalStateException("模型配置不存在");
        if (!config.hasApiKey()) {
            throw new IllegalStateException("未配置模型 API Key，请在“模型设置”中填写，或通过环境变量提供");
        }
        if (config.modelName() == null || config.modelName().isBlank()) {
            throw new IllegalStateException("未配置模型名称");
        }
        if (AppConfigurationService.PROVIDER_OPENAI_COMPATIBLE.equalsIgnoreCase(config.provider())
                && (config.baseUrl() == null || config.baseUrl().isBlank())) {
            throw new IllegalStateException("OpenAI 兼容接口需要配置服务地址");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
