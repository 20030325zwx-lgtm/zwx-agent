package com.zwx.zwxagent.knowledge.retrieval;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * rerank 池显式截断：融合候选超过重排上限时，送精排的必须恰为 maxDocuments 条
 * 且截断发生在调用方（有日志、索引可映射），不再依赖 rerank 服务内静默 limit（评审 #3）。
 */
class AgentKnowledgeRagServiceRerankPoolTest {

    private static final String TENANT = "default";
    private static final String AGENT = "super";

    @Test
    void rerankPoolTruncatedExplicitlyToMaxDocuments() throws Exception {
        List<Document> pool = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            pool.add(chunk("doc-" + i, "候选内容" + i, 0.9 - i * 0.01));
        }
        List<Integer> rerankSizes = new ArrayList<>();
        AgentKnowledgeRagService service = service(pool, rerankSizes);
        service.retrieveWithContext(TENANT, AGENT, "任意问题");
        Assertions.assertEquals(1, rerankSizes.size());
        Assertions.assertEquals(20, rerankSizes.getFirst(), "送 rerank 的候选必须显式截到 maxDocuments=20");
    }

    private AgentKnowledgeRagService service(List<Document> pool, List<Integer> rerankSizes) {
        VectorStore stubStore = new VectorStore() {
            @Override
            public void add(List<Document> documents) { }

            @Override
            public void delete(List<String> idList) { }

            @Override
            public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) { }

            @Override
            public List<Document> similaritySearch(SearchRequest request) {
                return pool;
            }
        };
        DashScopeRerankService rerankStub = new DashScopeRerankService("sk-test", false, "gte-rerank-v2", 1000) {
            @Override
            public Optional<List<DashScopeRerankService.RerankHit>> rerank(String query, List<String> documents) {
                rerankSizes.add(documents.size());
                return Optional.empty();
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
            set(service, "siblingExpansionBudgetMs", 1800L);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return service;
    }

    private static Document chunk(String id, String text, double score) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", id);
        metadata.put("tenantId", TENANT);
        metadata.put("agentKey", AGENT);
        metadata.put("lifecycleStatus", "ACTIVE");
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
