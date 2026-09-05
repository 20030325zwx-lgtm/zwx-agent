package com.zwx.zwxagent.rag;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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

    private DashScopeRerankService disabledService() {
        return new DashScopeRerankService("sk-test", false, "gte-rerank-v2", 1000);
    }
}
