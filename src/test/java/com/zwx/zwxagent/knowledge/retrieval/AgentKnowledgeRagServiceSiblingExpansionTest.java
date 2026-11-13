package com.zwx.zwxagent.knowledge.retrieval;

import com.zwx.zwxagent.knowledge.vector.KnowledgeVectorStore;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * 父子分块的检索侧实现：命中文本块后按「同文档同章节」端口方法拉取兄弟块进上下文；
 * 开关关闭时行为与旧版一致。兄弟查询已走 KnowledgeVectorStore.fetchSiblings
 * （不漏块、chunkIndex 阅读序、不花 embedding）。
 */
class AgentKnowledgeRagServiceSiblingExpansionTest {

    private static final String TENANT = "default";
    private static final String AGENT = "super";

    /** 兄弟扩展调用记录：tenantId/agentKey/documentId/sectionPath/limit。 */
    private record SiblingCall(String tenantId, String agentKey, String documentId, String sectionPath, int limit) {
    }

    @Test
    void hitExpandsWithSectionSiblings() throws Exception {
        Document hit = chunk("hit-1", "审批流程的总则说明", "ACTIVE", Map.of(
                "sourceType", "TEXT", "documentId", "doc-1", "sectionPath", "第三章 > 审批流程", "chunkIndex", 3));
        Document sibling = chunk("sib-1", "审批流程的详细步骤说明", "ACTIVE", Map.of(
                "sourceType", "TEXT", "documentId", "doc-1", "sectionPath", "第三章 > 审批流程", "chunkIndex", 4));
        List<SiblingCall> calls = new ArrayList<>();
        AgentKnowledgeRagService service = service(List.of(hit), siblingCall -> {
            calls.add(siblingCall);
            return List.of(hit, sibling);
        });
        set(service, "siblingExpansionEnabled", true);
        AgentKnowledgeRagResult result = service.retrieveWithContext(TENANT, AGENT, "审批流程");
        Assertions.assertTrue(result.context().contains("审批流程的详细步骤说明"), "兄弟块应进入回答上下文");
        Assertions.assertEquals(1, calls.size(), "兄弟块应通过一次同节端口调用获得");
        Assertions.assertEquals("doc-1", calls.getFirst().documentId());
        Assertions.assertEquals("第三章 > 审批流程", calls.getFirst().sectionPath());
    }

    @Test
    void figureChunksAreNotExpanded() throws Exception {
        Document figureHit = chunk("fig-1", "[图纸|流程图] 审批流程图", "ACTIVE", Map.of(
                "sourceType", "FIGURE", "documentId", "doc-1", "sectionPath", "第三章 > 审批流程"));
        List<SiblingCall> calls = new ArrayList<>();
        AgentKnowledgeRagService service = service(List.of(figureHit), siblingCall -> {
            calls.add(siblingCall);
            return List.of();
        });
        set(service, "siblingExpansionEnabled", true);
        service.retrieveWithContext(TENANT, AGENT, "审批流程图");
        Assertions.assertEquals(0, calls.size(), "图纸块由描述 chunk 自带上下文，不做兄弟扩展");
    }

    @Test
    void longSectionQueriesWithExtraPlusOneLimit() throws Exception {
        // 同节块数超过 extra：端口 LIMIT = extra + 1（含命中自身），长节不漏由存储层按阅读序保证
        Document hit = chunk("hit-1", "总则", "ACTIVE", Map.of(
                "sourceType", "TEXT", "documentId", "doc-1", "sectionPath", "第一章", "chunkIndex", 1));
        List<SiblingCall> calls = new ArrayList<>();
        AgentKnowledgeRagService service = service(List.of(hit), siblingCall -> {
            calls.add(siblingCall);
            return List.of();
        });
        set(service, "siblingExpansionEnabled", true);
        set(service, "siblingExpansionExtra", 4);
        service.retrieveWithContext(TENANT, AGENT, "总则");
        Assertions.assertEquals(1, calls.size());
        Assertions.assertEquals(5, calls.getFirst().limit(), "LIMIT 应为 extra+1（含命中自身）");
        Assertions.assertEquals("doc-1", calls.getFirst().documentId(), "documentId 作为过滤参数");
        Assertions.assertEquals(TENANT, calls.getFirst().tenantId(), "兄弟扩展必须带租户 scope");
        Assertions.assertEquals(AGENT, calls.getFirst().agentKey(), "兄弟扩展必须带 agentKey scope");
    }

    private AgentKnowledgeRagService service(List<Document> pool,
                                             java.util.function.Function<SiblingCall, List<Document>> siblingStub) {
        KnowledgeVectorStore stubStore = new KnowledgeVectorStore() {
            @Override
            public void add(List<Document> documents) { }

            @Override
            public void delete(List<String> idList) { }

            @Override
            public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) { }

            @Override
            public List<Document> similaritySearch(SearchRequest request) {
                Assertions.assertTrue(String.valueOf(request.getFilterExpression()).contains("lifecycleStatus"),
                        "主召回必须约束 lifecycleStatus");
                return pool;
            }

            @Override
            public List<Document> bm25Search(String tenantId, String agentKey, String query, int limit) {
                return List.of();
            }

            @Override
            public List<Document> hybridSearch(String tenantId, String agentKey, String query,
                                               int limit, double vectorSimilarityThreshold) {
                // stub：融合池即向量池
                return pool;
            }

            @Override
            public List<Document> fetchSiblings(String tenantId, String agentKey, String documentId,
                                                String sectionPath, int limit) {
                return siblingStub.apply(new SiblingCall(tenantId, agentKey, documentId, sectionPath, limit));
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
            set(service, "siblingExpansionBudgetMs", 1800L);
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
