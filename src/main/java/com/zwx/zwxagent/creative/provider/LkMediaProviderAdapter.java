package com.zwx.zwxagent.creative.provider;

import com.zwx.zwxagent.creative.domain.InputSnapshot;
import com.zwx.zwxagent.creative.domain.ResolvedMediaModel;
import com.zwx.zwxagent.creative.support.CreativeJson;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 街影 token / lk888（api.lk888.ai）视频适配器：平台异步媒体协议，不是 OpenAI /v1/videos。
 * 契约来源：网关自身 404 提示（2026-11-03 无密钥探测）
 * - 提交：POST {base}/media/generate，body 传 model（视频模型名）与 prompt；
 * - 轮询：GET {base}/media/status?task_id=xxx；
 * - 可用视频模型：GET {base}/skills/models?type=video（需密钥，配置前核对）。
 * 该网关明确不提供 OpenAI 视频格式端点（POST/GET /v1/videos 均 404），故不能复用 openai-video 适配器。
 * 字段名按平台常见返回做防御式解析（task_id / id / data.*）；在拿到真实密钥完成实测前，
 * 模型档案标记 UNVERIFIED，不宣称兼容性。图生视频契约未核实，本适配器只声明文生视频。
 */
@Component
public class LkMediaProviderAdapter implements MediaProviderAdapter {

    private final HttpClient httpClient;

    public LkMediaProviderAdapter() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String key() {
        return "lk-media";
    }

    @Override
    public SubmitResult submit(ResolvedMediaModel model, InputSnapshot input, String submissionKey) {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("model", model.modelName());
        payload.put("prompt", input.prompt());
        Object seconds = input.parameters().get("seconds");
        if (seconds != null && !String.valueOf(seconds).isBlank()) {
            payload.put("seconds", String.valueOf(seconds));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(openAiVideoEndpoint(model.baseUrl(), "/media/generate")))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + model.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(CreativeJson.toJson(payload)))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return classifySubmit(response);
        } catch (Exception exception) {
            return SubmitResult.unknown(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private SubmitResult classifySubmit(HttpResponse<String> response) {
        int status = response.statusCode();
        var json = CreativeJson.parse(response.body());
        if (status == 200 || status == 201) {
            String taskId = firstNonBlank(
                    json.path("task_id").asText(""),
                    json.path("data").path("task_id").asText(""),
                    json.path("id").asText(""),
                    json.path("data").path("id").asText(""));
            if (!taskId.isBlank()) return SubmitResult.accepted(taskId);
            // 2xx 但没有任务号：无法对账，按提交不明处理
            return SubmitResult.unknown("媒体提交返回 2xx 但缺少 task_id");
        }
        String message = firstNonBlank(
                json.path("error").path("message").asText(""),
                json.path("message").asText(""));
        String code = firstNonBlank(
                json.path("error").path("code").asText(""),
                json.path("code").asText(""));
        if (status == 429) {
            long retryAfter = response.headers().firstValueAsLong("Retry-After").orElse(30);
            return SubmitResult.throttled(message.isBlank() ? "Provider rate limited" : message, retryAfter);
        }
        if (status == 401 || status == 403) {
            return SubmitResult.rejected("PROVIDER_AUTH_FAILED",
                    message.isBlank() ? "Provider auth failed" : message);
        }
        if (status >= 400 && status < 500) {
            return SubmitResult.rejected(code.isBlank() ? "PROVIDER_REJECTED" : code, message);
        }
        return SubmitResult.unknown("HTTP " + status + (message.isBlank() ? "" : " " + message));
    }

    @Override
    public QueryResult query(ResolvedMediaModel model, String providerTaskId) {
        String path = "/media/status?task_id=" + URLEncoder.encode(providerTaskId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(openAiVideoEndpoint(model.baseUrl(), path)))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + model.apiKey())
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 404) {
                return QueryResult.failed("PROVIDER_TASK_NOT_FOUND", "上游媒体任务不存在：" + providerTaskId);
            }
            if (status == 429) return QueryResult.unknown("Provider rate limited");
            if (status >= 400) {
                var err = CreativeJson.parse(response.body());
                return QueryResult.unknown("HTTP " + status + " " + firstNonBlank(
                        err.path("message").asText(""), err.path("error").path("message").asText("")));
            }
            var json = CreativeJson.parse(response.body());
            var data = json.has("data") && json.path("data").isObject() ? json.path("data") : json;
            String state = firstNonBlank(data.path("status").asText(""), data.path("state").asText(""))
                    .toLowerCase(java.util.Locale.ROOT);
            return switch (state) {
                case "succeeded", "success", "completed", "done" -> {
                    String url = firstNonBlank(
                            data.path("video_url").asText(""),
                            data.path("url").asText(""),
                            data.path("output").isTextual() ? data.path("output").asText("") : "",
                            data.path("output").path("video_url").asText(""),
                            data.path("output").path("url").asText(""),
                            data.path("result").path("video_url").asText(""),
                            data.path("result").path("url").asText(""));
                    if (url.isBlank()) {
                        yield QueryResult.failed("PROVIDER_OUTPUT_MISSING", "任务已完成但响应中没有视频地址");
                    }
                    yield QueryResult.succeeded(List.of(new Output(url, null, "video/mp4")));
                }
                case "failed", "error", "cancelled", "canceled" -> {
                    var error = data.path("error");
                    yield QueryResult.failed(
                            firstNonBlank(error.path("code").asText(""), data.path("code").asText("PROVIDER_FAILED")),
                            firstNonBlank(error.path("message").asText(""), data.path("message").asText("视频生成失败")));
                }
                case "queued", "pending", "processing", "running", "in_progress", "submitted" ->
                        QueryResult.running();
                default -> QueryResult.unknown("未知媒体任务状态：" + state);
            };
        } catch (Exception exception) {
            return QueryResult.unknown(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    /** 与 openai-video 相同的 baseUrl 约定：以 /v数字 结尾直接拼接，否则补 /v1。 */
    private static String openAiVideoEndpoint(String baseUrl, String path) {
        String base = baseUrl == null || baseUrl.isBlank()
                ? "https://api.lk888.ai" : baseUrl.replaceAll("/+$", "");
        String effective = base.matches(".*(/v[0-9]+)$") ? base : base + "/v1";
        return effective + path;
    }
}
