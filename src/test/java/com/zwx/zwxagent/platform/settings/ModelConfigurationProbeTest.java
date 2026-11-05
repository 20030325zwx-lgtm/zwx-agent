package com.zwx.zwxagent.platform.settings;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 探针两阶段逻辑回归：/models 连通+鉴权判定、媒体模型（不支持对话接口/上游包装错误）识别。
 */
class ModelConfigurationProbeTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String startServer(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] payload = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private ResolvedModelConfig config(String baseUrl) {
        return new ResolvedModelConfig("test", AppConfigurationService.PROVIDER_OPENAI_COMPATIBLE,
                baseUrl, "gpt-image-2", "sk-test", "TEST");
    }

    private ModelConfigurationProbe probe() {
        return new ModelConfigurationProbe(null);
    }

    @Test
    void modelsProbeCountsModelsOnSuccess() throws Exception {
        String base = startServer(200, "{\"data\":[{\"id\":\"gpt-image-2\"},{\"id\":\"gpt-image-2.5-flare\"}]}");
        var result = probe().probeModels(config(base));
        assertEquals(ModelConfigurationProbe.ModelsStatus.OK, result.status());
        assertEquals(2, result.modelCount());
    }

    @Test
    void modelsProbeMapsUnauthorized() throws Exception {
        String base = startServer(401, "{\"error\":\"invalid key\"}");
        var result = probe().probeModels(config(base));
        assertEquals(ModelConfigurationProbe.ModelsStatus.UNAUTHORIZED, result.status());
    }

    @Test
    void modelsProbeTreats404AsNotExposed() throws Exception {
        String base = startServer(404, "not found");
        var result = probe().probeModels(config(base));
        assertEquals(ModelConfigurationProbe.ModelsStatus.NOT_FOUND, result.status());
    }

    @Test
    void modelsProbeReportsUnreachable() {
        var result = probe().probeModels(config("http://127.0.0.1:1/v1"));
        assertEquals(ModelConfigurationProbe.ModelsStatus.UNREACHABLE, result.status());
    }

    @Test
    void classifiesMediaModelChatRejections() {
        assertTrue(ModelConfigurationProbe.isChatUnsupported(
                "This model is not supported on the Chat Completions endpoint"));
        assertTrue(ModelConfigurationProbe.isChatUnsupported(
                "400 - {\"error\":{\"message\":\"This model is not supported on the Chat Completions endpoint\"}}"));
        assertTrue(ModelConfigurationProbe.isUpstreamRejected(
                "404 - {\"error\":{\"message\":\"openai_error\",\"type\":\"bad_response_status_code\"}}"));
        assertFalse(ModelConfigurationProbe.isChatUnsupported("connection timed out"));
        assertFalse(ModelConfigurationProbe.isChatUnsupported(null));
    }
}
