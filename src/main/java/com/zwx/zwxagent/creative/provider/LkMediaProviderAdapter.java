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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 街影 token / lk888（api.lk888.ai）平台异步媒体适配器。
 *
 * 契约来源：网关 /v1/skills 文档 + gk-video-3.5 模型详情（2026-11-03 用真实 Key 获取）：
 * - 提交 POST {base}/media/generate：{"model","prompt","params":{...}} → {"code":200,"data":{"task_id":123456}}
 * - 轮询 GET {base}/skills/task-status?task_id=xxx → {state,status,is_final,progress,result_url,error}
 *   终态判定只用 is_final（成功时 result_url 有值；失败时 error 非空），状态文案随语言变化不可靠。
 * - gk-video-3.5 仅支持图生视频：params.images 必填（公网直链或 data:base64，≤10MB），
 *   aspect_ratio（16:9/9:16/1:1/3:2/2:3）、resolution（720p/480p）、duration（1~15 秒）均必填；
 *   未传时本适配器给保守默认（16:9 / 720p / 时长取 seconds 或 5），不静默改变用户已选时长。
 *
 * 平台不提供 OpenAI /v1/videos 端点，故不能复用 openai-video；该网关也无取消协议，cancel 默认不支持。
 * 失败分类码（content_policy_violation 等）原样透传给上层，便于区分可重试与不可重试。
 */
@Component
public class LkMediaProviderAdapter implements MediaProviderAdapter {

    /** 平台 base64 图片上限 10MB（gk-video-3.5 契约）。 */
    private static final long MAX_BASE64_IMAGE_BYTES = 10L * 1024 * 1024;

    private static final String DEFAULT_ASPECT_RATIO = "16:9";
    private static final String DEFAULT_RESOLUTION = "720p";
    private static final String DEFAULT_DURATION = "5";

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
        // 文生/无参考图的调用对 gk-video 不成立；这里交给平台拒绝，但先给出明确本地提示更友好
        return send(model, input, null);
    }

    @Override
    public SubmitResult submit(ResolvedMediaModel model, InputSnapshot input, String submissionKey,
                               Map<java.util.UUID, ReferenceAsset> referenceAssets) {
        ReferenceAsset reference = referenceAssets == null || referenceAssets.isEmpty()
                ? null : referenceAssets.values().iterator().next();
        return send(model, input, reference);
    }

    /** 首帧引用：优先用可公网读取的 URL；没有 URL 时按平台契约改传 data:base64（≤10MB）。 */
    private static String referenceImage(ReferenceAsset reference) {
        String externalUrl = reference.externalUrl();
        if (externalUrl != null && !externalUrl.isBlank()) return externalUrl;
        byte[] bytes = reference.bytes();
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BASE64_IMAGE_BYTES) return null;
        String mime = reference.mimeType() == null || reference.mimeType().isBlank()
                ? "image/png" : reference.mimeType();
        return "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private SubmitResult send(ResolvedMediaModel model, InputSnapshot input, ReferenceAsset reference) {
        if (reference == null) {
            return SubmitResult.rejected("REFERENCE_REQUIRED",
                    "该视频模型只支持图生视频：请提供一张首帧图片（参考素材）后再提交");
        }
        String imageReference = referenceImage(reference);
        if (imageReference == null) {
            return SubmitResult.rejected("REFERENCE_UNAVAILABLE",
                    "首帧图不可用：对象存储未提供可读取地址，且图片超过 10MB 的 base64 上限");
        }
        var params = new LinkedHashMap<String, Object>();
        List<String> images = new ArrayList<>();
        images.add(imageReference);
        params.put("images", images);
        String seconds = String.valueOf(input.parameters().getOrDefault("seconds", "")).trim();
        params.put("duration", seconds.isBlank() ? DEFAULT_DURATION : seconds);
        params.put("aspect_ratio", DEFAULT_ASPECT_RATIO);
        params.put("resolution", DEFAULT_RESOLUTION);

        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", model.modelName());
        payload.put("prompt", input.prompt());
        payload.put("params", params);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint(model.baseUrl(), "/media/generate")))
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
        var data = json.has("data") && json.path("data").isObject() ? json.path("data") : json;
        if (status == 200 || status == 201) {
            // 文档主推 data.task_id（数字），保留兼容字段读取
            String taskId = firstNonBlank(
                    data.path("task_id").asText(""),
                    data.path("id").asText(""));
            if (!taskId.isBlank()) return SubmitResult.accepted(taskId);
            return SubmitResult.unknown("媒体提交返回 2xx 但缺少 data.task_id");
        }
        String message = firstNonBlank(
                json.path("msg").asText(""),
                json.path("error").path("message").asText(""),
                json.path("message").asText(""));
        String errorCode = firstNonBlank(
                json.path("error_code").asText(""),
                data.path("reason_code").asText(""),
                json.path("code").asText(""));
        if (status == 429) {
            long retryAfter = response.headers().firstValueAsLong("Retry-After").orElse(30);
            return SubmitResult.throttled(message.isBlank() ? "Provider rate limited" : message, retryAfter);
        }
        if (status == 401 || status == 403) {
            return SubmitResult.rejected("PROVIDER_AUTH_FAILED",
                    message.isBlank() ? "Provider auth failed" : message);
        }
        if (status == 402 || "insufficient_balance".equalsIgnoreCase(errorCode)) {
            return SubmitResult.rejected("PROVIDER_BALANCE_INSUFFICIENT",
                    message.isBlank() ? "供应商余额不足" : message);
        }
        if (status >= 400 && status < 500) {
            return SubmitResult.rejected(errorCode.isBlank() ? "PROVIDER_REJECTED" : errorCode, message);
        }
        return SubmitResult.unknown("HTTP " + status + (message.isBlank() ? "" : " " + message));
    }

    @Override
    public QueryResult query(ResolvedMediaModel model, String providerTaskId) {
        String path = "/skills/task-status?task_id=" + URLEncoder.encode(providerTaskId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint(model.baseUrl(), path)))
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
                        err.path("msg").asText(""), err.path("message").asText(""),
                        err.path("error").path("message").asText("")));
            }
            var json = CreativeJson.parse(response.body());
            var data = json.has("data") && json.path("data").isObject() ? json.path("data") : json;
            // 终态只认 is_final；state 用于区分成功/失败；status/status_group 是本地化文案不可靠
            boolean isFinal = data.path("is_final").asBoolean(false);
            String state = data.path("state").asText("").toLowerCase(java.util.Locale.ROOT);
            String error = data.path("error").isNull() ? "" : data.path("error").asText("");
            if (!isFinal) {
                // 非终态即使 state=success 也继续轮询（防止上游过早标记）
                return QueryResult.running();
            }
            String resultUrl = data.path("result_url").asText("");
            boolean success = "success".equals(state) || "succeeded".equals(state) || "completed".equals(state)
                    || ("".equals(state) && !resultUrl.isBlank() && error.isBlank())
                    || ("".equals(state) && error.isBlank() && "已完成".equals(data.path("status_group").asText("")));
            if (success) {
                if (resultUrl.isBlank()) {
                    return QueryResult.failed("PROVIDER_OUTPUT_MISSING", "任务已完成但响应中没有结果地址");
                }
                return QueryResult.succeeded(List.of(new Output(resultUrl, null, "video/mp4")));
            }
            if (!error.isBlank() || "failed".equals(state) || "error".equals(state)) {
                String code = firstNonBlank(
                        data.path("error_code").asText(""),
                        data.path("reason_code").asText(""),
                        "PROVIDER_FAILED");
                return QueryResult.failed(code, error.isBlank() ? "视频生成失败" : error);
            }
            return QueryResult.unknown("未知终态：state=" + state);
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
    private static String endpoint(String baseUrl, String path) {
        String base = baseUrl == null || baseUrl.isBlank()
                ? "https://api.lk888.ai" : baseUrl.replaceAll("/+$", "");
        String effective = base.matches(".*(/v[0-9]+)$") ? base : base + "/v1";
        return effective + path;
    }
}
