package com.zwx.zwxagent.creative.provider;

import com.zwx.zwxagent.creative.domain.InputSnapshot;
import com.zwx.zwxagent.creative.domain.ResolvedMediaModel;
import com.zwx.zwxagent.platform.settings.ModelCatalogService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DashScope 文生图适配器（C07）：按模型契约文档（docs/design/2026-10-29-ai-creative-model-contract.md）实现。
 * 提交：POST /api/v1/services/aigc/text2image/image-synthesis（X-DashScope-Async: enable）；
 * 查询：GET /api/v1/tasks/{taskId}；取消：HTTP 端点未提供 → Unsupported（契约 U3）。
 * 响应分类：2xx+task_id → Accepted；2xx 无 task_id → Unknown；429 → Unknown（throttled，可退避重试提交）；
 * 4xx 参数/鉴权错误 → Rejected；5xx/超时 → Unknown。
 */
@Component
public class DashScopeImageAdapter implements MediaProviderAdapter {

    public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com";

    private final HttpClient httpClient;
    private final boolean allowLocalEndpoints;

    public DashScopeImageAdapter(
            @Value("${creative.download-allow-local:false}") boolean allowLocalEndpoints) {
        this.allowLocalEndpoints = allowLocalEndpoints;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String key() {
        return "dashscope-text2image";
    }

    @Override
    public SubmitResult submit(ResolvedMediaModel model, InputSnapshot input, String submissionKey) {
        String baseUrl = normalize(model.baseUrl());
        String size = String.valueOf(input.parameters().getOrDefault("size", "1024*1024"));
        boolean watermark = Boolean.parseBoolean(String.valueOf(
                input.parameters().getOrDefault("watermark", "false")));
        String body = com.zwx.zwxagent.creative.support.CreativeJson.toJson(Map.of(
                "model", model.modelName(),
                "input", Map.of("prompt", input.prompt()),
                "parameters", Map.of("size", size, "n", 1,
                        "watermark", watermark, "prompt_extend", false)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/services/aigc/text2image/image-synthesis"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + model.apiKey())
                .header("X-DashScope-Async", "enable")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            var json = com.zwx.zwxagent.creative.support.CreativeJson.parse(response.body());
            String taskId = json.path("output").path("task_id").asText("");
            if (status >= 200 && status < 300 && !taskId.isBlank()) {
                return MediaProviderAdapter.SubmitResult.accepted(taskId);
            }
            String errorCode = json.path("code").asText("");
            String message = json.path("message").asText(json.path("error").asText(""));
            if (status == 429) {
                // 明确的限流拒绝：上游未创建任务，可退避后重试提交（SUBMITTING→QUEUED）
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
                        errorCode.isBlank() ? "PROVIDER_REJECTED" : errorCode, message);
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
        String baseUrl = normalize(model.baseUrl());
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/tasks/" + providerTaskId))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + model.apiKey())
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            var json = com.zwx.zwxagent.creative.support.CreativeJson.parse(response.body());
            String state = json.path("output").path("task_status").asText("");
            if (response.statusCode() == 429) return QueryResult.unknown("Provider rate limited");
            if (response.statusCode() >= 400) {
                return QueryResult.unknown("HTTP " + response.statusCode());
            }
            return switch (state) {
                case "SUCCEEDED" -> {
                    var outputs = new java.util.ArrayList<Output>();
                    for (var node : json.path("output").path("results")) {
                        String url = node.path("url").asText("");
                        if (!url.isBlank()) outputs.add(Output.ofUrl(url));
                    }
                    yield QueryResult.succeeded(List.copyOf(outputs));
                }
                case "FAILED" -> QueryResult.failed(
                        json.path("output").path("code").asText("PROVIDER_FAILED"),
                        json.path("output").path("message").asText(""));
                case "CANCELED" -> QueryResult.failed("CANCELED", "上游任务已取消");
                case "PENDING", "RUNNING" -> QueryResult.running();
                default -> QueryResult.unknown("未知任务状态：" + state);
            };
        } catch (Exception exception) {
            return QueryResult.unknown(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private static String normalize(String baseUrl) {
        return baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.replaceAll("/+$", "");
    }

    /** 仅供测试注入 baseUrl 的辅助（生产 baseUrl 来自冻结快照）。 */
    static URI endpoint(String baseUrl, String path) {
        return URI.create(normalize(baseUrl) + path);
    }

    /** 可选：下载结果（调度器 FETCHING 阶段使用，带 SSRF 校验）。 */
    public Optional<byte[]> downloadChecked(String url, long maxBytes) {
        return Optional.of(new SafeMediaDownloader(allowLocalEndpoints, maxBytes).download(url));
    }
}
