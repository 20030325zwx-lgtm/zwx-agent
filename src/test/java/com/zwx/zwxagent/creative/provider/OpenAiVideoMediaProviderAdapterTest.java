package com.zwx.zwxagent.creative.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zwx.zwxagent.creative.domain.InputSnapshot;
import com.zwx.zwxagent.creative.domain.ParameterSchema;
import com.zwx.zwxagent.creative.domain.ResolvedMediaModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI Sora 兼容视频适配器（V1-A）的 HTTP 行为分类：
 * 201+id → ACCEPTED；401/403 → AUTH；429+Retry-After → throttled；
 * 2xx 缺 id → Unknown（不对账）；4xx → REJECTED；5xx/异常 → Unknown；
 * query 状态映射 processing/succeeded（url 与 /content 兜底）/failed/404。
 * 与 OpenAiImagesMediaProviderAdapterTest 同一模式：本地 HttpServer Mock，不起 Spring。
 */
class OpenAiVideoMediaProviderAdapterTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger submitCount = new AtomicInteger();
    private final AtomicReference<String> lastSubmitBody = new AtomicReference<>();
    private final AtomicReference<String> lastQueryPath = new AtomicReference<>();
    private volatile Responder submitResponder = request -> reply(201,
            "{\"id\":\"video_123\",\"object\":\"video\",\"status\":\"processing\"}");
    private volatile Responder queryResponder = request -> reply(200,
            "{\"id\":\"video_123\",\"status\":\"processing\",\"progress\":0}");

    @FunctionalInterface
    private interface Responder {
        HttpReply respond(String body) throws Exception;
    }

    private record HttpReply(int status, String body, Map<String, String> headers) {
    }

    private static HttpReply reply(int status, String body) {
        return new HttpReply(status, body, Map.of());
    }

    private static HttpReply reply(int status, String body, Map<String, String> headers) {
        return new HttpReply(status, body, headers);
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/videos", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/videos") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    submitCount.incrementAndGet();
                    lastSubmitBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    write(exchange, submitResponder.respond(""));
                    return;
                }
                if (path.matches(".*/videos/[^/]+$")) {
                    lastQueryPath.set(path);
                    write(exchange, queryResponder.respond(""));
                    return;
                }
                write(exchange, reply(404, "{\"error\":{\"message\":\"not found\"}}"));
            } catch (Exception exception) {
                write(exchange, reply(500, "mock failure"));
            }
        });
        server.start();
        // 裸域名形式：覆盖 /v1 自动补全的路径约定
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ResolvedMediaModel model() {
        return new ResolvedMediaModel(15L, 36L, "openai-compatible", baseUrl,
                "sd_2.5_special_1080p", "sk-test", "openai-video",
                ParameterSchema.openAiVideo(), false, 1L);
    }

    private InputSnapshot input(String operation, Map<String, Object> parameters) {
        return new InputSnapshot(operation, "一只猫在草地上奔跑", parameters, List.of());
    }

    @Test
    void submit201WithIdIsAccepted() {
        var result = new OpenAiVideoMediaProviderAdapter().submit(model(),
                input(ParameterSchema.TEXT_TO_VIDEO, Map.of("seconds", "5", "size", "1280x720")), "key-1");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.ACCEPTED, result.outcome());
        Assertions.assertEquals("video_123", result.providerTaskId());
    }

    @Test
    void submitIsJsonBodyWithModelPromptSeconds() {
        new OpenAiVideoMediaProviderAdapter().submit(model(),
                input(ParameterSchema.TEXT_TO_VIDEO, Map.of("seconds", "5")), "key-1");
        String body = lastSubmitBody.get();
        Assertions.assertTrue(body.contains("\"model\""), () -> "缺少 model 字段：" + body);
        Assertions.assertTrue(body.contains("sd_2.5_special_1080p"));
        Assertions.assertTrue(body.contains("\"seconds\":\"5\""));
        Assertions.assertFalse(body.contains("\"size\""), () -> "size 字段未验证不应默认发送：" + body);
    }

    @Test
    void imageToVideoSendsSignedReferenceUrlInJson() {
        String signedUrl = "https://assets.example.com/reference.png?signature=short-lived";
        var result = new OpenAiVideoMediaProviderAdapter().submit(model(),
                input(ParameterSchema.IMAGE_TO_VIDEO, Map.of()), "key-1",
                Map.of(UUID.randomUUID(), new MediaProviderAdapter.ReferenceAsset(
                        new byte[]{-119, 80, 78, 71, 13, 10, 26, 10}, "image/png", signedUrl)));
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.ACCEPTED, result.outcome());
        String body = lastSubmitBody.get();
        Assertions.assertTrue(body.contains("\"input_reference\":\"" + signedUrl),
                () -> "缺少首帧临时 URL：" + body);
    }

    @Test
    void imageToVideoRejectsLocalReferenceWithoutCallingProvider() {
        int before = submitCount.get();
        var result = new OpenAiVideoMediaProviderAdapter().submit(model(),
                input(ParameterSchema.IMAGE_TO_VIDEO, Map.of()), "key-1",
                Map.of(UUID.randomUUID(), new MediaProviderAdapter.ReferenceAsset(
                        new byte[]{-119, 80, 78, 71}, "image/png", null)));
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.REJECTED, result.outcome());
        Assertions.assertEquals("REFERENCE_URL_UNAVAILABLE", result.errorCode());
        Assertions.assertEquals(before, submitCount.get());
    }

    @Test
    void submit2xxWithoutIdIsUnknown() {
        submitResponder = request -> reply(200, "{\"status\":\"processing\"}");
        var result = new OpenAiVideoMediaProviderAdapter().submit(model(),
                input(ParameterSchema.TEXT_TO_VIDEO, Map.of()), "key-1");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.UNKNOWN, result.outcome());
    }

    @Test
    void submitAuthFailureIsRejected() {
        submitResponder = request -> reply(401, "{\"error\":{\"message\":\"bad key\"}}");
        var result = new OpenAiVideoMediaProviderAdapter().submit(model(),
                input(ParameterSchema.TEXT_TO_VIDEO, Map.of()), "key-1");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.REJECTED, result.outcome());
        Assertions.assertEquals("PROVIDER_AUTH_FAILED", result.errorCode());
    }

    @Test
    void submit429IsThrottledWithRetryAfter() {
        submitResponder = request -> reply(429, "{\"error\":{\"message\":\"rate\"}}", Map.of("Retry-After", "17"));
        var result = new OpenAiVideoMediaProviderAdapter().submit(model(),
                input(ParameterSchema.TEXT_TO_VIDEO, Map.of()), "key-1");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.UNKNOWN, result.outcome());
        Assertions.assertEquals("PROVIDER_RATE_LIMITED", result.errorCode());
        Assertions.assertEquals(17, result.retryAfterSeconds());
    }

    @Test
    void submit4xxIsRejectedAnd5xxIsUnknown() {
        submitResponder = request -> reply(400, "{\"code\":\"bad_size\",\"message\":\"size\",\"data\":null}");
        var rejected = new OpenAiVideoMediaProviderAdapter().submit(model(),
                input(ParameterSchema.TEXT_TO_VIDEO, Map.of()), "key-1");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.REJECTED, rejected.outcome());
        Assertions.assertEquals("bad_size", rejected.errorCode());

        submitResponder = request -> reply(502, "bad gateway");
        var unknown = new OpenAiVideoMediaProviderAdapter().submit(model(),
                input(ParameterSchema.TEXT_TO_VIDEO, Map.of()), "key-1");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.UNKNOWN, unknown.outcome());
    }

    @Test
    void queryProcessingIsRunning() {
        var result = new OpenAiVideoMediaProviderAdapter().query(model(), "video_123");
        Assertions.assertEquals(MediaProviderAdapter.QueryState.RUNNING, result.state());
        Assertions.assertEquals("/v1/videos/video_123", lastQueryPath.get());
    }

    @Test
    void queryCompletedWithVideoUrlUsesVideoUrlOutput() {
        // 实测成功响应（2026-11-01）：status=completed + video_url（上游 OSS 直链）
        queryResponder = request -> reply(200, "{\"status\":\"completed\",\"progress\":100,\"video_url\":\"https://cdn.example.com/v.mp4\"}");
        var result = new OpenAiVideoMediaProviderAdapter().query(model(), "video_123");
        Assertions.assertEquals(MediaProviderAdapter.QueryState.SUCCEEDED, result.state());
        Assertions.assertEquals("https://cdn.example.com/v.mp4", result.outputs().getFirst().url());
        Assertions.assertEquals("video/mp4", result.outputs().getFirst().mimeType());
    }

    @Test
    void queryGatewayErrorStructureIsParsed() {
        // 中转站网关错误结构 {"code","message"}（无 error 包装）也要解析出可读信息
        queryResponder = request -> reply(400, "{\"code\":\"plugin_request_invalid\",\"message\":\"buildSubmitRequest failed\",\"data\":null}");
        var result = new OpenAiVideoMediaProviderAdapter().query(model(), "video_123");
        Assertions.assertEquals(MediaProviderAdapter.QueryState.UNKNOWN, result.state());
        Assertions.assertTrue(result.message().contains("buildSubmitRequest failed"));
    }

    @Test
    void querySucceededWithoutUrlFallsBackToContentEndpoint() {
        queryResponder = request -> reply(200, "{\"status\":\"succeeded\",\"progress\":100}");
        var result = new OpenAiVideoMediaProviderAdapter().query(model(), "video_abc");
        Assertions.assertEquals(MediaProviderAdapter.QueryState.SUCCEEDED, result.state());
        Assertions.assertTrue(result.outputs().getFirst().url().endsWith("/v1/videos/video_abc/content"));
        Assertions.assertEquals("video/mp4", result.outputs().getFirst().mimeType());
    }

    @Test
    void queryFailedMapsError() {
        queryResponder = request -> reply(200, "{\"status\":\"failed\",\"error\":{\"code\":\"nsfw\",\"message\":\"blocked\"}}");
        var result = new OpenAiVideoMediaProviderAdapter().query(model(), "video_123");
        Assertions.assertEquals(MediaProviderAdapter.QueryState.FAILED, result.state());
        Assertions.assertEquals("nsfw", result.errorCode());
    }

    @Test
    void query404IsDefiniteFailureNotUnknown() {
        queryResponder = request -> reply(404, "{\"error\":{\"message\":\"no task\"}}");
        var result = new OpenAiVideoMediaProviderAdapter().query(model(), "video_gone");
        Assertions.assertEquals(MediaProviderAdapter.QueryState.FAILED, result.state());
        Assertions.assertEquals("PROVIDER_TASK_NOT_FOUND", result.errorCode());
    }

    @Test
    void queryUnknownStatusIsUnknown() {
        queryResponder = request -> reply(200, "{\"status\":\"weird\"}");
        var result = new OpenAiVideoMediaProviderAdapter().query(model(), "video_123");
        Assertions.assertEquals(MediaProviderAdapter.QueryState.UNKNOWN, result.state());
    }

    private static void write(HttpExchange exchange, HttpReply reply) {
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Exception ignored) {
            // 客户端断开等 IO 异常：Mock 场景忽略
        }
    }
}
