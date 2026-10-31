package com.zwx.zwxagent.creative.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zwx.zwxagent.creative.domain.InputSnapshot;
import com.zwx.zwxagent.creative.domain.ResolvedMediaModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI images 兼容适配器（中转站）的 HTTP 行为分类：b64_json/url 双解析、
 * 401、429+Retry-After、4xx 参数错误、5xx/超时 → Unknown。打本地 Mock 的真实请求，
 * 纯 JUnit 不起 Spring（与 DashScopeImageAdapterTest 同一模式）。
 */
class OpenAiImagesMediaProviderAdapterTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger submitCount = new AtomicInteger();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<byte[]> lastBodyBytes = new AtomicReference<>();

    private volatile Responder submitResponder = request -> reply(200,
            "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(PNG_BYTES) + "\"}]}");

    private static final byte[] PNG_BYTES = new byte[]{-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13};

    @FunctionalInterface
    private interface Responder {
        HttpReply respond(String body) throws Exception;
    }

    private record HttpReply(int status, String body, Map<String, String> headers) {
    }

    private static HttpReply reply(int status, String body) {
        return new HttpReply(status, body, Map.of());
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            submitCount.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastBody.set(body);
            try {
                write(exchange, submitResponder.respond(body));
            } catch (Exception exception) {
                write(exchange, reply(500, "mock failure"));
            }
        });
        // image_edit：multipart 请求，读取整个请求体供断言
        server.createContext("/v1/images/edits", exchange -> {
            submitCount.incrementAndGet();
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            lastBodyBytes.set(bodyBytes);
            lastBody.set(new String(bodyBytes, StandardCharsets.ISO_8859_1));
            try {
                write(exchange, submitResponder.respond(""));
            } catch (Exception exception) {
                write(exchange, reply(500, "mock failure"));
            }
        });
        server.start();
        // 故意用裸域名形式，覆盖 /v1 自动补全的路径约定
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void write(HttpExchange exchange, HttpReply reply) throws java.io.IOException {
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        reply.headers().forEach((key, value) -> exchange.getResponseHeaders().add(key, value));
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private OpenAiImagesMediaProviderAdapter adapter() {
        return new OpenAiImagesMediaProviderAdapter();
    }

    private ResolvedMediaModel model() {
        return new ResolvedMediaModel(1L, 2L, "openai-compatible", baseUrl,
                "flux-dev", "test-key", "openai-images", null, false, 0);
    }

    private InputSnapshot input(Map<String, Object> parameters) {
        return new InputSnapshot("text_to_image", "一只戴帽子的柯基", parameters, List.of());
    }

    @Test
    void submitParsesB64JsonIntoBytes() {
        var result = adapter().submit(model(), input(Map.of("size", "1024*1024")), "sub-1");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.COMPLETED, result.outcome());
        Assertions.assertNotNull(result.outputs());
        Assertions.assertEquals(1, result.outputs().size());
        Assertions.assertArrayEquals(PNG_BYTES, result.outputs().get(0).bytes());
        // 请求体约定：不发 response_format；DashScope 风格的 1024*1024 归一为 1024x1024
        String body = lastBody.get();
        Assertions.assertTrue(body.contains("\"n\":1"));
        Assertions.assertFalse(body.contains("response_format"));
        Assertions.assertTrue(body.contains("\"size\":\"1024x1024\""), body);
    }

    @Test
    void submitParsesUrlOutput() {
        submitResponder = request -> reply(200,
                "{\"data\":[{\"url\":\"http://127.0.0.1:1/abc.png\"}]}");
        var result = adapter().submit(model(), input(Map.of()), "sub-2");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.COMPLETED, result.outcome());
        Assertions.assertEquals("http://127.0.0.1:1/abc.png", result.outputs().get(0).url());
    }

    @Test
    void emptyDataIsRejected() {
        submitResponder = request -> reply(200, "{\"data\":[]}");
        var result = adapter().submit(model(), input(Map.of()), "sub-3");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.REJECTED, result.outcome());
        Assertions.assertEquals("EMPTY_RESULT", result.errorCode());
    }

    @Test
    void authFailureIsRejected() {
        submitResponder = request -> reply(401, "{\"error\":{\"message\":\"bad key\"}}");
        var result = adapter().submit(model(), input(Map.of()), "sub-4");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.REJECTED, result.outcome());
        Assertions.assertEquals("PROVIDER_AUTH_FAILED", result.errorCode());
        Assertions.assertEquals("bad key", result.message());
    }

    @Test
    void rateLimitReturnsRetryAfter() {
        submitResponder = request -> new HttpReply(429, "{\"error\":{\"message\":\"slow down\"}}",
                Map.of("Retry-After", "7"));
        var result = adapter().submit(model(), input(Map.of()), "sub-5");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.UNKNOWN, result.outcome());
        Assertions.assertEquals("PROVIDER_RATE_LIMITED", result.errorCode());
        Assertions.assertEquals(7, result.retryAfterSeconds());
    }

    @Test
    void parameterErrorIsRejected() {
        submitResponder = request -> reply(400,
                "{\"error\":{\"code\":\"invalid_size\",\"message\":\"size not supported\"}}");
        var result = adapter().submit(model(), input(Map.of("size", "1x1")), "sub-6");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.REJECTED, result.outcome());
        Assertions.assertEquals("invalid_size", result.errorCode());
        Assertions.assertEquals("size not supported", result.message());
    }

    @Test
    void serverErrorIsUnknown() {
        submitResponder = request -> reply(502, "{\"error\":{\"message\":\"upstream unavailable\"}}");
        var result = adapter().submit(model(), input(Map.of()), "sub-7");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.UNKNOWN, result.outcome());
        Assertions.assertTrue(result.message().contains("502"));
    }

    @Test
    void queryAlwaysUnknownForSyncProtocol() {
        var result = adapter().query(model(), "any-task");
        Assertions.assertEquals(MediaProviderAdapter.QueryState.UNKNOWN, result.state());
    }

    /** image_edit：multipart /v1/images/edits，参考图以 image[] 数组上传，响应解析同 generations。 */
    @Test
    void editSubmitsMultipartWithReferenceImages() throws Exception {
        byte[] modelPhoto = PNG_BYTES;
        byte[] garment = new byte[]{-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4, 5, 6};
        var references = java.util.Map.of(java.util.UUID.randomUUID(), modelPhoto,
                java.util.UUID.randomUUID(), garment);
        var result = adapter().submit(model(),
                new InputSnapshot("image_edit", "让模特穿上参考的上衣与裤子", Map.of("size", "1024*1024"), List.of()),
                "sub-edit", references);
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.COMPLETED, result.outcome());
        Assertions.assertArrayEquals(PNG_BYTES, result.outputs().get(0).bytes());
        byte[] body = lastBodyBytes.get();
        Assertions.assertTrue(contains(body, "name=\"model\"".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertTrue(contains(body, "flux-dev".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertTrue(contains(body, "name=\"prompt\"".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertTrue(contains(body, "让模特穿上参考的上衣与裤子".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertTrue(contains(body, "name=\"size\"".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertTrue(contains(body, "1024x1024".getBytes(StandardCharsets.UTF_8)));
        Assertions.assertEquals(2, countOccurrences(body, "name=\"image[]\"".getBytes(StandardCharsets.UTF_8)),
                "应有 2 个 image[] 文件段");
        Assertions.assertTrue(contains(body, modelPhoto), "模特图字节应原样上传");
        Assertions.assertTrue(contains(body, garment), "上衣图字节应原样上传");
        // 空 referenceAssets 时回退 JSON generations（不带 image[] 段）
        adapter().submit(model(), new InputSnapshot("text_to_image", "一只橘猫", Map.of(), List.of()), "sub-gen");
        Assertions.assertFalse(lastBody.get().contains("image[]"));
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        return indexOf(haystack, needle, 0) >= 0;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int countOccurrences(byte[] haystack, byte[] needle) {
        int count = 0;
        int at = indexOf(haystack, needle, 0);
        while (at >= 0) {
            count++;
            at = indexOf(haystack, needle, at + 1);
        }
        return count;
    }

    @Test
    void syncTimeoutIsDeclaredForLeaseExtension() {
        Assertions.assertTrue(adapter().syncSubmitTimeoutSeconds() > 0,
                "同步适配器必须声明超时，供调度器延长租约");
    }

    @Test
    void endpointFollowsV1Convention() {
        Assertions.assertEquals("http://r.example.com/v1/images/generations",
                OpenAiImagesMediaProviderAdapter.endpoint("http://r.example.com", "/images/generations"));
        Assertions.assertEquals("http://r.example.com/v1/images/generations",
                OpenAiImagesMediaProviderAdapter.endpoint("http://r.example.com/v1", "/images/generations"));
        Assertions.assertEquals("http://r.example.com/api/paas/v4/images/generations",
                OpenAiImagesMediaProviderAdapter.endpoint("http://r.example.com/api/paas/v4/", "/images/generations"));
    }
}
