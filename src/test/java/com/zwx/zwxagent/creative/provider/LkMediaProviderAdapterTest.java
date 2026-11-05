package com.zwx.zwxagent.creative.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.zwx.zwxagent.creative.domain.InputSnapshot;
import com.zwx.zwxagent.creative.domain.ParameterSchema;
import com.zwx.zwxagent.creative.domain.ResolvedMediaModel;
import com.zwx.zwxagent.creative.support.CreativeJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class LkMediaProviderAdapterTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger submitCount = new AtomicInteger();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/media/generate", exchange -> {
            submitCount.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"code\":200,\"data\":{\"task_id\":123456}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void imageToVideoSendsReferenceUrlInImages() {
        String signedUrl = "https://assets.example.com/first-frame.png?signature=short-lived";
        UUID assetId = UUID.randomUUID();
        var input = new InputSnapshot(ParameterSchema.IMAGE_TO_VIDEO, "男主在办公桌前喝咖啡",
                Map.of("seconds", "8"), List.of(assetId));

        var result = new LkMediaProviderAdapter().submit(model(), input, "sub-1",
                Map.of(assetId, new MediaProviderAdapter.ReferenceAsset(
                        new byte[]{-119, 80, 78, 71}, "image/png", signedUrl)));

        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.ACCEPTED, result.outcome());
        Assertions.assertEquals("123456", result.providerTaskId());
        Assertions.assertEquals(1, submitCount.get());
        JsonNode body = CreativeJson.parse(requestBody.get());
        Assertions.assertEquals("gk-video-3.5", body.path("model").asText());
        Assertions.assertEquals("男主在办公桌前喝咖啡", body.path("prompt").asText());
        Assertions.assertEquals(signedUrl, body.path("params").path("images").get(0).asText());
        Assertions.assertEquals("8", body.path("params").path("duration").asText());
    }

    @Test
    void missingPublicReferenceUrlFallsBackToBase64DataUri() {
        UUID assetId = UUID.randomUUID();
        var input = new InputSnapshot(ParameterSchema.IMAGE_TO_VIDEO, "测试", Map.of(), List.of(assetId));

        // 自建 MinIO 无公网入口时不再直接拒绝：按平台契约改传 data:base64
        var result = new LkMediaProviderAdapter().submit(model(), input, "sub-1",
                Map.of(assetId, new MediaProviderAdapter.ReferenceAsset(new byte[]{1, 2, 3}, "image/png", null)));

        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.ACCEPTED, result.outcome());
        Assertions.assertEquals(1, submitCount.get());
        JsonNode body = CreativeJson.parse(requestBody.get());
        Assertions.assertEquals("data:image/png;base64,AQID",
                body.path("params").path("images").get(0).asText());
    }

    @Test
    void referenceWithoutUrlOrBytesIsRejectedBeforeProviderCall() {
        UUID assetId = UUID.randomUUID();
        var input = new InputSnapshot(ParameterSchema.IMAGE_TO_VIDEO, "测试", Map.of(), List.of(assetId));

        var result = new LkMediaProviderAdapter().submit(model(), input, "sub-1",
                Map.of(assetId, new MediaProviderAdapter.ReferenceAsset(new byte[0], "image/png", null)));

        Assertions.assertEquals(MediaProviderAdapter.SubmitOutcome.REJECTED, result.outcome());
        Assertions.assertEquals("REFERENCE_UNAVAILABLE", result.errorCode());
        Assertions.assertEquals(0, submitCount.get());
    }

    private ResolvedMediaModel model() {
        return new ResolvedMediaModel(1L, 2L, "lk-media", baseUrl, "gk-video-3.5",
                "sk-test", "lk-media", ParameterSchema.openAiVideo(), true, 1L);
    }
}
