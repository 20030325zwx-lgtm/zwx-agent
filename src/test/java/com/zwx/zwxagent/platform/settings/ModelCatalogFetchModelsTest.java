package com.zwx.zwxagent.platform.settings;

import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 中转站（OpenAI 兼容聚合网关）模型拉取：/v1/models 增量入库、重复拉取幂等、
 * 协议与归属限制。打本地 Mock，不访问真实中转站。
 * allow-local-model-endpoints：baseUrl 用本地回环 Mock，等同桌面模式的放行策略。
 */
@SpringBootTest(properties = {"app.config.allow-local-model-endpoints=true"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelCatalogFetchModelsTest {

    private static final String TEST_USER = "catalog-fetch-models-user";

    @Resource
    private ModelCatalogService catalogService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    private HttpServer server;
    private String baseUrl;
    private long providerId;

    @BeforeAll
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] bytes = """
                    {"data":[{"id":"qwen-plus"},{"id":"flux-dev"},{"id":"gpt-image-1"},{"id":"qwen-plus"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    void stopServer() {
        if (server != null) server.stop(0);
        if (providerId > 0) {
            jdbcTemplate.update("DELETE FROM model_provider_model WHERE provider_id = ?", providerId);
            jdbcTemplate.update("DELETE FROM model_provider WHERE id = ?", providerId);
        }
    }

    @Test
    void fetchesAndUpsertsModelsIncrementally() {
        ModelCatalogService.ProviderView provider = catalogService.createProvider(TEST_USER,
                new ModelCatalogService.ProviderRequest("拉取测试中转站", "openai-compatible",
                        baseUrl, "sk-test", true, false));
        providerId = provider.id();

        ModelCatalogService.FetchModelsResult first = catalogService.fetchModels(TEST_USER, providerId);
        Assertions.assertEquals(3, first.total(), "重复 id 应去重");
        Assertions.assertEquals(3, first.added());

        var view = catalogService.catalog(TEST_USER).providers().stream()
                .filter(p -> p.id() == providerId).findFirst().orElseThrow();
        Assertions.assertEquals(3, view.models().size());
        Assertions.assertTrue(view.models().stream().allMatch(model -> !model.enabled()),
                "新拉取的模型默认停用");
        Assertions.assertTrue(view.models().stream().allMatch(model -> model.capabilities().isEmpty()),
                "新拉取的模型不自动声明能力");

        // 第二次拉取：增量幂等
        ModelCatalogService.FetchModelsResult second = catalogService.fetchModels(TEST_USER, providerId);
        Assertions.assertEquals(0, second.added());

        // 拉取的生图模型可被勾选能力并启用（updateModelEntry 正常路径）
        Long fluxRowId = view.models().stream()
                .filter(model -> "flux-dev".equals(model.modelId())).findFirst().orElseThrow().id();
        var updated = catalogService.updateModelEntry(TEST_USER, providerId, fluxRowId,
                new ModelCatalogService.ModelEntryRequest("flux-dev", "FLUX 生图",
                        java.util.List.of("image_generation"), true));
        Assertions.assertTrue(updated.models().stream()
                .filter(model -> model.id() == fluxRowId).findFirst().orElseThrow()
                .capabilities().contains("image_generation"));
    }

    @Test
    void rejectsNonOpenAiCompatibleProviders() {
        ModelCatalogService.ProviderView provider = catalogService.createProvider(TEST_USER,
                new ModelCatalogService.ProviderRequest("拉取测试DashScope", "dashscope",
                        null, "sk-test", true, false));
        long dashscopeId = provider.id();
        try {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> catalogService.fetchModels(TEST_USER, dashscopeId));
        } finally {
            jdbcTemplate.update("DELETE FROM model_provider WHERE id = ?", dashscopeId);
        }
    }
}
