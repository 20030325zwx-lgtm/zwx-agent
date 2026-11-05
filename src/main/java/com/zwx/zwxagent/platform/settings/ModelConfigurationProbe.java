package com.zwx.zwxagent.platform.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 供应商连通性探测（两阶段）：
 * 1) OpenAI 兼容接口先用 GET {base}/models 验证网络可达与密钥有效——这一步与模型类型无关，
 *    图片/视频中转站也能通过；
 * 2) 再做一次对话探测，用于确认对话模型可用；返回“模型不支持 Chat Completions”或同类上游错误时，
 *    视为连接与鉴权正常、模型属媒体类型，给出明确说明而不是笼统的“连接失败”。
 */
@Component
public class ModelConfigurationProbe {

    private static final Duration MODELS_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CHAT_TIMEOUT = Duration.ofSeconds(30);

    private final DynamicChatModelFactory modelFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ModelConfigurationProbe(DynamicChatModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    public ModelTestResult test(ResolvedModelConfig config) {
        long startedAt = System.currentTimeMillis();
        ModelsProbe models = isOpenAiCompatible(config) ? probeModels(config) : null;
        if (models != null && models.status() == ModelsStatus.UNAUTHORIZED) {
            return failure("鉴权失败：API Key 无效或未配置（/models 返回 " + models.detail() + "）", config, startedAt);
        }
        if (models != null && models.status() == ModelsStatus.UNREACHABLE) {
            return failure("连接失败：" + models.detail(), config, startedAt);
        }
        String modelsHint = models != null && models.status() == ModelsStatus.OK
                ? "，模型列表 " + models.modelCount() + " 个" : "";
        try {
            String answer = chatPing(config);
            String normalized = answer == null ? "" : answer.trim().replaceAll("\\s+", " ");
            if (normalized.length() > 80) normalized = normalized.substring(0, 80);
            return new ModelTestResult(true,
                    (normalized.isBlank() ? "连接成功（模型未返回文本）" : "连接成功：" + normalized) + modelsHint,
                    config.provider(), config.modelName(), config.source(),
                    System.currentTimeMillis() - startedAt);
        } catch (Exception exception) {
            String message = messageOf(exception);
            if (models != null && models.status() == ModelsStatus.OK
                    && (isChatUnsupported(message) || isUpstreamRejected(message))) {
                // 鉴权与模型列表已通过，只是该模型不走对话接口（图片/视频模型的中转站属正常情况）
                return new ModelTestResult(true,
                        "连接成功（鉴权通过" + modelsHint + "；该模型不支持对话接口，疑似图片/视频模型，请按能力绑定使用）。"
                                + "上游返回：" + message,
                        config.provider(), config.modelName(), config.source(),
                        System.currentTimeMillis() - startedAt);
            }
            return failure("连接失败：" + message + modelsHint, config, startedAt);
        }
    }

    private String chatPing(ResolvedModelConfig config) throws Exception {
        ChatClient client = ChatClient.builder(modelFactory.model(config)).build();
        return CompletableFuture
                .supplyAsync(() -> client.prompt().user("请只回复 OK，不要解释。").call().content())
                .get(CHAT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    /** GET {base}/models：验证网络可达与密钥有效（OpenAI 兼容约定，base 已含版本段时直接追加）。 */
    ModelsProbe probeModels(ResolvedModelConfig config) {
        URI uri;
        try {
            String base = config.baseUrl() == null ? "" : config.baseUrl().replaceAll("/+$", "");
            if (base.isBlank()) return new ModelsProbe(ModelsStatus.UNREACHABLE, -1, "服务地址为空");
            String path = base.matches(".*/v\\d+$") ? "/models" : "/v1/models";
            uri = URI.create(base + path);
        } catch (IllegalArgumentException exception) {
            return new ModelsProbe(ModelsStatus.UNREACHABLE, -1, "服务地址格式不正确");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET().timeout(MODELS_TIMEOUT);
        if (config.hasApiKey()) {
            builder.header("Authorization", "Bearer " + config.apiKey());
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) {
            return new ModelsProbe(ModelsStatus.UNREACHABLE, -1, shortMessage(exception));
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return new ModelsProbe(ModelsStatus.OK, countModels(response.body()), "HTTP " + status);
        }
        if (status == 401 || status == 403) {
            return new ModelsProbe(ModelsStatus.UNAUTHORIZED, -1, "HTTP " + status);
        }
        if (status == 404) {
            // 部分中转站不暴露 /models：退回对话探测判断
            return new ModelsProbe(ModelsStatus.NOT_FOUND, -1, "HTTP 404");
        }
        return new ModelsProbe(ModelsStatus.UNREACHABLE, -1,
                "服务返回 HTTP " + status + "：" + truncate(response.body()));
    }

    private int countModels(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            return data.isArray() ? data.size() : -1;
        } catch (Exception exception) {
            return -1;
        }
    }

    private static boolean isOpenAiCompatible(ResolvedModelConfig config) {
        return AppConfigurationService.PROVIDER_OPENAI_COMPATIBLE.equalsIgnoreCase(config.provider());
    }

    /** 上游明确表示该模型不能用于对话接口（例如图片/视频模型）。 */
    static boolean isChatUnsupported(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("not supported on the chat completions")
                || lower.contains("does not support chat")
                || lower.contains("only supports chat")
                || lower.contains("is not a chat model");
    }

    /** 中转站把上游失败包装成通用错误（bad_response_status_code）：连接与鉴权已通过，模型类型待定。 */
    static boolean isUpstreamRejected(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("bad_response_status_code");
    }

    private static String messageOf(Exception exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        String message = cause.getMessage() == null || cause.getMessage().isBlank()
                ? cause.getClass().getSimpleName() : cause.getMessage().replaceAll("[\\r\\n]", " ");
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private static String shortMessage(Exception exception) {
        String message = exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage().replaceAll("[\\r\\n]", " ");
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    private static String truncate(String body) {
        if (body == null) return "";
        String normalized = body.replaceAll("[\\r\\n]", " ");
        return normalized.length() > 160 ? normalized.substring(0, 160) : normalized;
    }

    private ModelTestResult failure(String message, ResolvedModelConfig config, long startedAt) {
        return new ModelTestResult(false, message, config.provider(), config.modelName(), config.source(),
                System.currentTimeMillis() - startedAt);
    }

    enum ModelsStatus { OK, UNAUTHORIZED, NOT_FOUND, UNREACHABLE }

    record ModelsProbe(ModelsStatus status, int modelCount, String detail) {
    }
}
