package com.zwx.zwxagent.platform.settings;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class ModelConfigurationProbe {

    private final DynamicChatModelFactory modelFactory;

    public ModelConfigurationProbe(DynamicChatModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    public ModelTestResult test(ResolvedModelConfig config) {
        long startedAt = System.currentTimeMillis();
        try {
            ChatClient client = ChatClient.builder(modelFactory.model(config)).build();
            String answer = CompletableFuture
                    .supplyAsync(() -> client.prompt().user("请只回复 OK，不要解释。").call().content())
                    .get(30, TimeUnit.SECONDS);
            String normalized = answer == null ? "" : answer.trim().replaceAll("\\s+", " ");
            if (normalized.length() > 80) normalized = normalized.substring(0, 80);
            return new ModelTestResult(true,
                    normalized.isBlank() ? "连接成功（模型未返回文本）" : "连接成功：" + normalized,
                    config.provider(), config.modelName(), config.source(),
                    System.currentTimeMillis() - startedAt);
        } catch (Exception exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            String message = cause.getMessage() == null || cause.getMessage().isBlank()
                    ? cause.getClass().getSimpleName() : cause.getMessage().replaceAll("[\\r\\n]", " ");
            if (message.length() > 240) message = message.substring(0, 240);
            return new ModelTestResult(false, "连接失败：" + message, config.provider(), config.modelName(),
                    config.source(), System.currentTimeMillis() - startedAt);
        }
    }
}
