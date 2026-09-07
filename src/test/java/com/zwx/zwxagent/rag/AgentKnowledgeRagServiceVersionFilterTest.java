package com.zwx.zwxagent.rag;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

class AgentKnowledgeRagServiceVersionFilterTest {

    private static final String TENANT = "default";
    private static final String AGENT = "love";

    @Test
    void onlyActiveLifecycleChunksEnterContext() {
        Document active = chunk("active-1", "退款政策：七天无理由退货", "ACTIVE", "refund-policy", 2, 0.9);
        Document archived = chunk("archived-1", "旧版退款政策：三十天退货", "ARCHIVED", "refund-policy", 1, 0.95);
        AgentKnowledgeRagService service = service(List.of(archived, active), Optional.empty());
        AgentKnowledgeRagResult result = service.retrieveWithContext(TENANT, AGENT, "退款政策是什么");
        Assertions.assertTrue(result.context().contains("七天无理由"));
        Assertions.assertFalse(result.context().contains("三十天"));
        Assertions.assertEquals(1, result.references().size());
        Assertions.assertEquals("refund-policy", result.references().get(0).logicalKey());
        Assertions.assertEquals(2, result.references().get(0).versionNo());
    }

    @Test
    void deduplicatesSameLogicalDocumentBeforeRerank() {
        Document newer = chunk("v2-1", "v2 退货期限七天", "ACTIVE", "refund-policy", 2, 0.8);
        Document newer2 = chunk("v2-2", "v2 退货流程说明", "ACTIVE", "refund-policy", 2, 0.75);
        Document other = chunk("other-1", "会员积分规则", "ACTIVE", "points-rule", 1, 0.7);
        List<DashScopeRerankService.RerankHit> hits = List.of(
                new DashScopeRerankService.RerankHit(0, 0.99),
                new DashScopeRerankService.RerankHit(1, 0.98));
        AgentKnowledgeRagService service = service(List.of(newer2, newer, other), Optional.of(hits));
        AgentKnowledgeRagResult result = service.retrieveWithContext(TENANT, AGENT, "退货规则");
        Assertions.assertEquals(2, result.references().size());
        Assertions.assertEquals("v2 退货期限七天", result.references().get(0).excerpt());
        Assertions.assertEquals("points-rule", result.references().get(1).logicalKey());
    }

    @Test
    void rerankFailureFallsBackToVectorOrderWithinThreshold() {
        Document strong = chunk("strong", "高分有效期内容", "ACTIVE", "rule-a", 3, 0.9);
        Document weak = chunk("weak", "低分低于阈值内容", "ACTIVE", "rule-b", 1, 0.5);
        AgentKnowledgeRagService service = service(List.of(weak, strong), Optional.empty());
        AgentKnowledgeRagResult result = service.retrieveWithContext(TENANT, AGENT, "任意问题");
        Assertions.assertEquals(1, result.references().size());
        Assertions.assertEquals("高分有效期内容", result.references().get(0).excerpt());
    }

    private AgentKnowledgeRagService service(List<Document> pool, Optional<List<DashScopeRerankService.RerankHit>> rerankHits) {
        VectorStore stubStore = new VectorStore() {
            @Override
            public void add(List<Document> documents) { }

            @Override
            public void delete(List<String> idList) { }

            @Override
            public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) { }

            @Override
            public List<Document> similaritySearch(SearchRequest request) {
                Assertions.assertTrue(request.getFilterExpression().toString().contains("lifecycleStatus"),
                        "filterExpression must constrain lifecycleStatus");
                return pool;
            }
        };
        DashScopeRerankService rerankStub = new DashScopeRerankService("sk-test", false, "gte-rerank-v2", 1000) {
            @Override
            public Optional<List<DashScopeRerankService.RerankHit>> rerank(String query, List<String> documents) {
                return rerankHits;
            }
        };
        AgentKnowledgeRagService service = new AgentKnowledgeRagService(stubStore, rerankStub);
        try {
            set(service, "ragExecutor", (Executor) Runnable::run);
            set(service, "topK", 3);
            set(service, "similarityThreshold", 0.55);
            set(service, "recallTopK", 15);
            set(service, "recallSimilarityThreshold", 0.4);
            set(service, "timeoutMs", 3000L);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return service;
    }

    private static Document chunk(String id, String text, String lifecycleStatus, String logicalKey, int versionNo, double score) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", id);
        metadata.put("tenantId", TENANT);
        metadata.put("agentKey", AGENT);
        metadata.put("filename", logicalKey + ".md");
        metadata.put("logicalKey", logicalKey);
        metadata.put("versionNo", versionNo);
        metadata.put("lifecycleStatus", lifecycleStatus);
        metadata.put("chunkIndex", 1);
        metadata.put("objectKey", "knowledge/" + id);
        return Document.builder().id(id).text(text).metadata(metadata).score(score).build();
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
