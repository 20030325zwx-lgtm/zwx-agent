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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A21（C07）：DashScopeImageAdapter 的 HTTP 行为分类——成功受理、401、429+Retry-After、
 * POST 5xx、查询错误、无 task_id 的 2xx。打本地 Mock 的真实请求，纯 JUnit 不起 Spring。
 */
class DashScopeImageAdapterTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger submitCount = new AtomicInteger();
    private final AtomicInteger queryCount = new AtomicInteger();

    private volatile Responder submitResponder = request -> reply(200,
            "{\"output\":{\"task_id\":\"task-1\",\"task_status\":\"PENDING\"},\"request_id\":\"r-1\"}");
    private volatile Responder queryResponder = request -> reply(200,
            "{\"output\":{\"task_id\":\"task-1\",\"task_status\":\"RUNNING\"},\"request_id\":\"r-1\"}");

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
        server.createContext("/api/v1/services/aigc/text2image/image-synthesis", exchange -> {
            submitCount.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                write(exchange, submitResponder.respond(body));
            } catch (Exception exception) {
                write(exchange, reply(500, "mock failure"));
            }
        });
        server.createContext("/api/v1/tasks/", exchange -> {
            queryCount.incrementAndGet();
            try {
                write(exchange, queryResponder.respond(""));
            } catch (Exception exception) {
                write(exchange, reply(500, "mock failure"));
            }
        });
        server.start();
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
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private ResolvedMediaModel modelWith(String url) {
        return new ResolvedMediaModel(1L, 1L, "dashscope", url, "wanx2.1-t2i-plus",
                "test-key", "dashscope-text2image", ParameterSchema.dashscopeTextToImage(), true, 1L);
    }

    private ResolvedMediaModel model() {
        return modelWith(baseUrl);
    }

    private final InputSnapshot input = new InputSnapshot("text_to_image", "一只猫",
            Map.of("size", "1024*1024"), List.of());

    private DashScopeImageAdapter adapter() {
        return new DashScopeImageAdapter(true);
    }

    @Test
    void submitAcceptedReturnsTaskId() {
        var result = adapter().submit(model(), input, "sub-1");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.ACCEPTED, result.outcome());
        Assertions.assertEquals("task-1", result.providerTaskId());
        Assertions.assertEquals(1, submitCount.get());
    }

    @Test
    void submitSuccessWithoutTaskIdIsNotAccepted() {
        submitResponder = request -> reply(200, "{\"output\":{}}");
        var result = adapter().submit(model(), input, "sub-no-task");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.UNKNOWN, result.outcome());
    }

    @Test
    void submitUnauthorizedIsRejectedAsAuthFailure() {
        submitResponder = request -> reply(401,
                "{\"code\":\"InvalidApiKey\",\"message\":\"No API-key provided.\"}");
        var result = adapter().submit(model(), input, "sub-auth");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.REJECTED, result.outcome());
        Assertions.assertEquals("PROVIDER_AUTH_FAILED", result.errorCode());
    }

    @Test
    void submitThrottledCarriesRetryAfter() {
        submitResponder = request -> new HttpReply(429, "{\"code\":\"Throttling\"}",
                Map.of("Retry-After", "17"));
        var result = adapter().submit(model(), input, "sub-429");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.UNKNOWN, result.outcome());
        Assertions.assertEquals("PROVIDER_RATE_LIMITED", result.errorCode());
        Assertions.assertEquals(17, result.retryAfterSeconds());
    }

    @Test
    void submitThrottledDefaultsRetryAfterWhenHeaderMissing() {
        submitResponder = request -> reply(429, "{\"code\":\"Throttling\"}");
        var result = adapter().submit(model(), input, "sub-429-no-header");
        Assertions.assertTrue(result.retryAfterSeconds() > 0);
        Assertions.assertEquals("PROVIDER_RATE_LIMITED", result.errorCode());
    }

    @Test
    void submitServerErrorIsUnknownNotRejected() {
        submitResponder = request -> reply(502, "Bad Gateway");
        var result = adapter().submit(model(), input, "sub-5xx");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.UNKNOWN, result.outcome(),
                "5xx 不能当作拒绝：上游可能已受理，禁止据此重发");
    }

    @Test
    void submitBadRequestIsRejectedWithProviderCode() {
        submitResponder = request -> reply(400,
                "{\"code\":\"InvalidParameter\",\"message\":\"size is invalid\"}");
        var result = adapter().submit(model(), input, "sub-400");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.REJECTED, result.outcome());
        Assertions.assertEquals("InvalidParameter", result.errorCode());
    }

    @Test
    void submitConnectionFailureIsUnknown() {
        // 停掉服务使提交无法建立连接：不确定是否受理，必须 Unknown 而非拒绝
        String deadUrl = baseUrl;
        server.stop(0);
        var result = adapter().submit(modelWith(deadUrl), input, "sub-dead");
        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.UNKNOWN, result.outcome());
    }

    @Test
    void querySucceededReturnsOutputUrls() {
        queryResponder = request -> reply(200, """
                {"output":{"task_id":"task-1","task_status":"SUCCEEDED",
                  "results":[{"url":"https://example.com/a.png"}]},
                 "usage":{"image_count":1},"request_id":"r"}
                """);
        var result = adapter().query(model(), "task-1");
        Assertions.assertEquals(MediaProviderAdapter.QueryState.SUCCEEDED, result.state());
        Assertions.assertEquals(1, result.outputs().size());
        Assertions.assertEquals("https://example.com/a.png", result.outputs().get(0).url());
    }

    @Test
    void queryFailedMapsCodeAndMessage() {
        queryResponder = request -> reply(200, """
                {"output":{"task_id":"task-1","task_status":"FAILED",
                  "code":"IPInfringementSuspect","message":"content flagged"}}
                """);
        var result = adapter().query(model(), "task-1");
        Assertions.assertEquals(MediaProviderAdapter.QueryState.FAILED, result.state());
        Assertions.assertEquals("IPInfringementSuspect", result.errorCode());
        Assertions.assertEquals("content flagged", result.message());
    }

    @Test
    void queryRunningAndUnknownStates() {
        queryResponder = request -> reply(200, "{\"output\":{\"task_status\":\"RUNNING\"}}");
        Assertions.assertEquals(MediaProviderAdapter.QueryState.RUNNING, adapter().query(model(), "t").state());
        queryResponder = request -> reply(200, "{\"output\":{\"task_status\":\"SOMETHING\"}}");
        Assertions.assertEquals(MediaProviderAdapter.QueryState.UNKNOWN, adapter().query(model(), "t").state());
    }

    @Test
    void queryErrorIsUnknownNotFailure() {
        queryResponder = request -> reply(500, "oops");
        var result = adapter().query(model(), "task-1");
        Assertions.assertEquals(MediaProviderAdapter.QueryState.UNKNOWN, result.state(),
                "查询失败只能重试查询，不得当作生成失败");
    }

    @Test
    void cancelIsUnsupportedByContract() {
        Assertions.assertEquals(MediaProviderAdapter.CancelOutcome.UNSUPPORTED,
                adapter().cancel(model(), "task-1"));
    }
}
