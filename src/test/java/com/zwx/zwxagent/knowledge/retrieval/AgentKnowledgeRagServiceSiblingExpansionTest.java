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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 父子分块的检索侧实现：命中文本块后按「同文档同章节」拉兄弟块进上下文；
 * 开关关闭时行为与旧版一致。
 */
class AgentKnowledgeRagServiceSiblingExpansionTest {

    private static final String TENANT = "default";
    private static final String AGENT = "super";

    @Test
    void hitExpandsWithSectionSiblings() throws Exception {
        Document hit = chunk("hit-1", "审批流程的总则说明", "ACTIVE", Map.of(
                "sourceType", "TEXT", "documentId", "doc-1", "sectionPath", "第三章 > 审批流程"));
        Document sibling = chunk("sib-1", "审批流程的详细步骤说明", "ACTIVE", Map.of(
                "sourceType", "TEXT", "documentId", "doc-1", "sectionPath", "第三章 > 审批流程"));
        AtomicInteger siblingSearches = new AtomicInteger();
        // 主召回返回命中；兄弟查询（带 sectionPath 过滤）返回同章节兄弟块（过滤由向量库承担）
        AgentKnowledgeRagService service2 = service(List.of(hit), Optional.empty(), request -> {
            if (String.valueOf(request.getFilterExpression()).contains("sectionPath")) {
                siblingSearches.incrementAndGet();
                return List.of(hit, sibling);
            }
            return List.of();
        });
        set(service2, "siblingExpansionEnabled", true);
        AgentKnowledgeRagResult result = service2.retrieveWithContext(TENANT, AGENT, "审批流程");
        System.out.println("DEBUG_CONTEXT=" + result.context().replace("\n","|"));
        Assertions.assertTrue(result.context().contains("审批流程的详细步骤说明"), "兄弟块应进入回答上下文");
        Assertions.assertTrue(siblingSearches.get() >= 1);
    }

    @Test
    void figureChunksAreNotExpanded() throws Exception {
        Document figureHit = chunk("fig-1", "[图纸|流程图] 审批流程图", "ACTIVE", Map.of(
                "sourceType", "FIGURE", "documentId", "doc-1", "sectionPath", "第三章 > 审批流程"));
        AtomicInteger siblingSearches = new AtomicInteger();
        AgentKnowledgeRagService service = service(List.of(figureHit), Optional.empty(), request -> {
            if (String.valueOf(request.getFilterExpression()).contains("sectionPath")) {
                siblingSearches.incrementAndGet();
                return List.of();
            }
            return List.of();
        });
        set(service, "siblingExpansionEnabled", true);
        service.retrieveWithContext(TENANT, AGENT, "审批流程图");
        Assertions.assertEquals(0, siblingSearches.get(), "图纸块由描述 chunk 自带上下文，不做兄弟扩展");
    }

    private interface StubSearch {
        List<Document> search(SearchRequest request);
    }

    private AgentKnowledgeRagService service(List<Document> pool, Optional<List<DashScopeRerankService.RerankHit>> rerankHits,
                                             StubSearch siblingSearch) {
        VectorStore stubStore = new VectorStore() {
            @Override
            public void add(List<Document> documents) { }

            @Override
            public void delete(List<String> idList) { }

            @Override
            public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) { }

            @Override
            public List<Document> similaritySearch(SearchRequest request) {
                String expression = String.valueOf(request.getFilterExpression());
                if (expression.contains("sectionPath")) return siblingSearch.search(request);
                Assertions.assertTrue(expression.contains("lifecycleStatus"), "主召回必须约束 lifecycleStatus");
                return pool;
            }
        };
        DashScopeRerankService rerankStub = new DashScopeRerankService("sk-test", false, "gte-rerank-v2", 1000) {
            @Override
            public Optional<List<DashScopeRerankService.RerankHit>> rerank(String query, List<String> documents) {
                return Optional.empty();
            }
        };
        AgentKnowledgeRagService service = new AgentKnowledgeRagService(stubStore, rerankStub);
        try {
            set(service, "ragExecutor", (Executor) Runnable::run);
            set(service, "topK", 3);
            set(service, "similarityThreshold", 0.0);
            set(service, "recallTopK", 15);
            set(service, "recallSimilarityThreshold", 0.0);
            set(service, "timeoutMs", 3000L);
            set(service, "siblingExpansionMaxHits", 2);
            set(service, "siblingExpansionExtra", 4);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return service;
    }

    private static Document chunk(String id, String text, String lifecycleStatus, Map<String, Object> extras) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", "doc-x");
        metadata.put("tenantId", TENANT);
        metadata.put("agentKey", AGENT);
        metadata.put("lifecycleStatus", lifecycleStatus);
        metadata.put("objectKey", "knowledge/" + id);
        metadata.putAll(extras);
        return Document.builder().id(id).text(text).metadata(metadata).score(0.9).build();
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
