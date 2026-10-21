package com.zwx.zwxagent.knowledge.retrieval;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

class DashScopeRerankServiceTest {

    @Test
    void parseResultsReturnsHitsInResponseOrder() {
        DashScopeRerankService service = disabledService();
        String json = """
                {"request_id":"test","output":{"results":[
                    {"index":2,"relevance_score":0.91},
                    {"index":0,"relevance_score":0.35}]},"usage":{"total_tokens":10}}
                """;
        Optional<List<DashScopeRerankService.RerankHit>> hits = service.rerank(null, List.of());
        Assertions.assertTrue(hits.isEmpty());
        List<DashScopeRerankService.RerankHit> parsed = service.parseResults(json, 3);
        Assertions.assertEquals(2, parsed.size());
        Assertions.assertEquals(2, parsed.get(0).index());
        Assertions.assertEquals(0.91, parsed.get(0).score(), 1e-6);
        Assertions.assertEquals(0, parsed.get(1).index());
    }

    @Test
    void parseResultsRejectsInvalidPayload() {
        DashScopeRerankService service = disabledService();
        Assertions.assertNull(service.parseResults("{\"output\":{}}", 3));
        Assertions.assertNull(service.parseResults("not-json", 3));
        Assertions.assertNull(service.parseResults(
                "{\"output\":{\"results\":[{\"index\":5,\"relevance_score\":0.5}]}}", 3));
    }

    @Test
    void rerankIsSkippedWhenDisabledOrTrivialInput() {
        DashScopeRerankService disabled = disabledService();
        Assertions.assertTrue(disabled.rerank("query", List.of("a", "b")).isEmpty());

        DashScopeRerankService enabledNoKey = new DashScopeRerankService("", true, "gte-rerank-v2", 1000);
        Assertions.assertTrue(enabledNoKey.rerank("query", List.of("a", "b")).isEmpty());

        DashScopeRerankService placeholderKey = new DashScopeRerankService("your-api-key", true, "gte-rerank-v2", 1000);
        Assertions.assertTrue(placeholderKey.rerank("query", List.of("a", "b")).isEmpty());

        Assertions.assertTrue(disabled.rerank("query", List.of("only")).isEmpty());
        Assertions.assertTrue(disabled.rerank(" ", List.of("a", "b")).isEmpty());
    }

    @Test
    void sendsBearerTokenAndParsesLiveResponse() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = """
                    {"output":{"results":[{"index":0,"relevance_score":0.9},{"index":1,"relevance_score":0.1}]}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            DashScopeRerankService service = new DashScopeRerankService("sk-test-key", true, "gte-rerank-v2", 2000, endpoint);
            Optional<List<DashScopeRerankService.RerankHit>> hits = service.rerank("query", List.of("doc-1", "doc-2"));
            Assertions.assertTrue(hits.isPresent());
            Assertions.assertEquals(2, hits.get().size());
            Assertions.assertEquals("Bearer sk-test-key", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    private DashScopeRerankService disabledService() {
        return new DashScopeRerankService("sk-test", false, "gte-rerank-v2", 1000);
    }
}
