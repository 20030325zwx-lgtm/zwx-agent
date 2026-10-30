package com.zwx.zwxagent.creative.provider;

import com.zwx.zwxagent.creative.domain.InputSnapshot;
import com.zwx.zwxagent.creative.domain.ResolvedMediaModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI images 兼容适配器（中转站接入）：对接 one-api/new-api 等 OpenAI 兼容聚合网关的
 * POST {base}/v1/images/generations（同步返回）。
 * 不发送 response_format：gpt-image-1 拒绝该参数且恒返 b64_json，dall-e 系默认返 url——
 * 两种返回都解析（b64_json 直接解码，url 交由调度器经 SafeMediaDownloader 下载）。
 * 该协议没有上游任务号：提交不明结果由调用方按观察窗口收敛（见调度器 handleSubmit/handleQueryOrIngest）。
 */
@Component
public class OpenAiImagesMediaProviderAdapter implements MediaProviderAdapter {

    private final HttpClient httpClient;

    // 默认值同时覆盖无 Spring 注入的测试场景；生产可经 creative.adapters.openai-images.timeout-seconds 覆盖
    @Value("${creative.adapters.openai-images.timeout-seconds:240}")
    private int timeoutSeconds = 240;

    public OpenAiImagesMediaProviderAdapter() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String key() {
        return "openai-images";
    }

    @Override
    public int syncSubmitTimeoutSeconds() {
        return timeoutSeconds;
    }

    @Override
    public SubmitResult submit(ResolvedMediaModel model, InputSnapshot input, String submissionKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model.modelName());
        payload.put("prompt", input.prompt());
        payload.put("n", 1);
        Object size = input.parameters().get("size");
        if (size != null && !String.valueOf(size).isBlank()) {
            // 创作台默认沿用 DashScope 的 1024*1024 写法；OpenAI images 用 WxH
            payload.put("size", String.valueOf(size).replace('*', 'x'));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint(model.baseUrl(), "/images/generations")))
                .timeout(Duration.ofSeconds(Math.max(30, timeoutSeconds)))
                .header("Authorization", "Bearer " + model.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        com.zwx.zwxagent.creative.support.CreativeJson.toJson(payload)))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            var json = com.zwx.zwxagent.creative.support.CreativeJson.parse(response.body());
            if (status >= 200 && status < 300) {
                var outputs = new ArrayList<Output>();
                for (var node : json.path("data")) {
                    String b64 = node.path("b64_json").asText("");
                    if (!b64.isBlank()) {
                        outputs.add(Output.ofBytes(Base64.getDecoder().decode(b64)));
                        continue;
                    }
                    String url = node.path("url").asText("");
                    if (!url.isBlank()) outputs.add(Output.ofUrl(url));
                }
                if (outputs.isEmpty()) {
                    return MediaProviderAdapter.SubmitResult.rejected("EMPTY_RESULT", "供应商返回空结果");
                }
                return MediaProviderAdapter.SubmitResult.completed(List.copyOf(outputs));
            }
            String message = json.path("error").path("message").asText(
                    json.path("message").asText(""));
            if (status == 429) {
                long retryAfter = response.headers().firstValueAsLong("Retry-After").orElse(30);
                return MediaProviderAdapter.SubmitResult.throttled(
                        message.isBlank() ? "Provider rate limited" : message, retryAfter);
            }
            if (status == 401 || status == 403) {
                return MediaProviderAdapter.SubmitResult.rejected("PROVIDER_AUTH_FAILED",
                        message.isBlank() ? "Provider auth failed" : message);
            }
            if (status >= 400 && status < 500) {
                return MediaProviderAdapter.SubmitResult.rejected(
                        json.path("error").path("code").asText("PROVIDER_REJECTED"), message);
            }
            return MediaProviderAdapter.SubmitResult.unknown(
                    "HTTP " + status + (message.isBlank() ? "" : " " + message));
        } catch (Exception exception) {
            return MediaProviderAdapter.SubmitResult.unknown(
                    exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    @Override
    public QueryResult query(ResolvedMediaModel model, String providerTaskId) {
        // 同步接口没有上游任务号，正常链路不会走到这里（提交即出结果）
        return QueryResult.unknown("同步接口无对账凭证");
    }

    /**
     * 与 DynamicChatModelFactory 的路径约定一致：baseUrl 以 /v数字 结尾时直接拼接，
     * 否则补 /v1（中转站常见 https://relay.example.com/v1 或裸域名两种写法）。
     */
    static String endpoint(String baseUrl, String path) {
        String base = baseUrl == null || baseUrl.isBlank()
                ? "https://api.openai.com" : baseUrl.replaceAll("/+$", "");
        String effective = base.matches(".*(/v[0-9]+)$") ? base : base + "/v1";
        return effective + path;
    }
}
