package com.zwx.zwxagent.creative.provider;

import com.zwx.zwxagent.creative.domain.InputSnapshot;
import com.zwx.zwxagent.creative.domain.ResolvedMediaModel;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * OpenAI Sora 兼容视频适配器（New API 中转站接入；协议经 KKAI 真实调用核实 2026-11-01）：
 * 提交 POST {base}/videos **JSON body**（中转站 sora 插件上游为 Seedance，明确拒绝 multipart）：
 * {"model","prompt","seconds"} → 200/201 {"id","status":"queued"}；
 * 轮询 GET {base}/videos/{id} → status queued/in_progress/**completed**（非 succeeded），
 * 成功响应带 **video_url**（上游 OSS 直链）；
 * 兜底下载 GET {base}/videos/{id}/content（video_url 缺失时使用）。
 * 错误结构两套并存：网关 {"code","message"} 与上游 {"error":{code,message}}，都要解析。
 * 真实验证记录：sd_2.0_special_720p 出片成功（5s，约 5 分钟，计费 1 次）；
 * sd_2.5_special_1080p 上游报 invalid_model（渠道侧问题）；size 字段未验证。
 * 图生视频：上游要求 JSON + 参考 URL（不接受文件上传），参考 URL 传输字段名未验证——
 * 本版如实拒绝并提示待开放，不猜测字段名（执行计划 V00 约定）。
 * 上游无取消协议：cancel 默认 UNSUPPORTED（已提交任务仍可能计费/完成）。
 */
@Component
public class OpenAiVideoMediaProviderAdapter implements MediaProviderAdapter {

    private final HttpClient httpClient;

    public OpenAiVideoMediaProviderAdapter() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String key() {
        return "openai-video";
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint(model.baseUrl(), "/videos")))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + model.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        com.zwx.zwxagent.creative.support.CreativeJson.toJson(payload)))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return classifySubmit(response);
        } catch (Exception exception) {
            return MediaProviderAdapter.SubmitResult.unknown(
                    exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    @Override
    public SubmitResult submit(ResolvedMediaModel model, InputSnapshot input, String submissionKey,
                               java.util.Map<java.util.UUID, byte[]> referenceAssets) {
        if (referenceAssets != null && !referenceAssets.isEmpty()) {
            // 上游（Seedance 插件）要求 JSON + 参考 URL；URL 传输字段名未经真实验证，不猜测计费
            return MediaProviderAdapter.SubmitResult.rejected("IMAGE_TO_VIDEO_PENDING",
                    "图生视频需要把首帧素材以公网 URL 交给上游，该传输方式尚未验证，暂未开放；请先使用文生视频");
        }
        return submit(model, input, submissionKey);
    }

    private MediaProviderAdapter.SubmitResult classifySubmit(HttpResponse<String> response) {
        int status = response.statusCode();
        var json = com.zwx.zwxagent.creative.support.CreativeJson.parse(response.body());
        if ((status == 200 || status == 201)) {
            String id = json.path("id").asText("");
            if (!id.isBlank()) {
                return MediaProviderAdapter.SubmitResult.accepted(id);
            }
            // 2xx 却没有任务号：无法对账，按提交不明处理，绝不猜测成功
            return MediaProviderAdapter.SubmitResult.unknown("视频提交返回 2xx 但缺少任务 id");
        }
        // 错误双结构：网关 {"code","message"} / OpenAI 风格 {"error":{code,message}}
        String message = firstNonBlank(
                json.path("error").path("message").asText(""),
                json.path("message").asText(""));
        String code = firstNonBlank(
                json.path("error").path("code").asText(""),
                json.path("code").asText(""));
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
                    code.isBlank() ? "PROVIDER_REJECTED" : code, message);
        }
        return MediaProviderAdapter.SubmitResult.unknown(
                "HTTP " + status + (message.isBlank() ? "" : " " + message));
    }

    @Override
    public QueryResult query(ResolvedMediaModel model, String providerTaskId) {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(endpoint(model.baseUrl(), "/videos/" + providerTaskId)))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + model.apiKey())
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 404) {
                // 上游任务不存在：明确失败，不重提交（可能是伪任务号或上游已清理）
                return MediaProviderAdapter.QueryResult.failed("PROVIDER_TASK_NOT_FOUND",
                        "上游视频任务不存在：" + providerTaskId);
            }
            if (status == 429) {
                return MediaProviderAdapter.QueryResult.unknown("Provider rate limited");
            }
            if (status >= 400) {
                var err = com.zwx.zwxagent.creative.support.CreativeJson.parse(response.body());
                return MediaProviderAdapter.QueryResult.unknown("HTTP " + status + " "
                        + firstNonBlank(err.path("message").asText(""), err.path("error").path("message").asText("")));
            }
            var json = com.zwx.zwxagent.creative.support.CreativeJson.parse(response.body());
            String state = json.path("status").asText("").toLowerCase(java.util.Locale.ROOT);
            return switch (state) {
                case "succeeded", "completed" -> {
                    // 实测成功响应：video_url（上游 OSS 直链）；url 为 Sora 官方字段，/content 兜底
                    String url = firstNonBlank(
                            json.path("video_url").asText(""),
                            json.path("url").asText(""));
                    Output output = url.isBlank()
                            ? new Output(endpoint(model.baseUrl(), "/videos/" + providerTaskId + "/content"),
                                    null, "video/mp4")
                            : new Output(url, null, "video/mp4");
                    yield MediaProviderAdapter.QueryResult.succeeded(List.of(output));
                }
                case "failed" -> {
                    var error = json.path("error");
                    yield MediaProviderAdapter.QueryResult.failed(
                            firstNonBlank(error.path("code").asText(""), json.path("code").asText("PROVIDER_FAILED")),
                            firstNonBlank(error.path("message").asText(""), json.path("message").asText("视频生成失败")));
                }
                case "processing", "queued", "in_progress", "pending" ->
                        MediaProviderAdapter.QueryResult.running();
                default -> MediaProviderAdapter.QueryResult.unknown("未知任务状态：" + state);
            };
        } catch (Exception exception) {
            return MediaProviderAdapter.QueryResult.unknown(
                    exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }

    /**
     * 与 OpenAiImagesMediaProviderAdapter 相同的路径约定：baseUrl 以 /v数字 结尾直接拼接，否则补 /v1。
     */
    static String endpoint(String baseUrl, String path) {
        String base = baseUrl == null || baseUrl.isBlank()
                ? "https://api.openai.com" : baseUrl.replaceAll("/+$", "");
        String effective = base.matches(".*(/v[0-9]+)$") ? base : base + "/v1";
        return effective + path;
    }
}
