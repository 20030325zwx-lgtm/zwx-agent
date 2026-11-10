package com.zwx.zwxagent.knowledge.retrieval;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 过滤表达式安全：含引号/过滤语法的 tenantId、agentKey、documentId 必须作为
 * 字面量进入 EQ 节点，不得改写过滤语义。旧实现用字符串拼接 filterExpression，
 * 恶意值可造成解析失败或越权读到其他租户/生命周期的切片；本测试是该注入面的回归防线。
 */
class AgentKnowledgeRagServiceFilterSafetyTest {

    private static final String TENANT = "default";
    private static final String AGENT = "love";

    @Test
    void retrieveTreatsHostileAgentKeyAsLiteral() {
        String hostileAgent = "love' || tenantId == 'other-tenant' || 'x";
        List<SearchRequest> captured = new ArrayList<>();
        AgentKnowledgeRagService service = service(captured);
        service.retrieve(TENANT, hostileAgent, "退款政策", 3, 0.0);
        Assertions.assertEquals(
                Map.of("tenantId", TENANT, "agentKey", hostileAgent, "lifecycleStatus", "ACTIVE"),
                equalities(captured));
    }

    @Test
    void verifyDocumentTreatsHostileDocumentIdAsLiteral() {
        String hostileDocumentId = "doc-1' || lifecycleStatus == 'ARCHIVED || '";
        List<SearchRequest> captured = new ArrayList<>();
        AgentKnowledgeRagService service = service(captured);
        service.verifyDocument(TENANT, AGENT, hostileDocumentId, "退款政策");
        Assertions.assertEquals(
                Map.of("tenantId", TENANT, "agentKey", AGENT, "documentId", hostileDocumentId),
                equalities(captured));
    }

    /** 恰好一次检索；表达式树只允许 AND/EQ 节点，键值对与期望完全一致（值不被解析成语法）。 */
    private static Map<String, Object> equalities(List<SearchRequest> captured) {
        Assertions.assertEquals(1, captured.size(), "应恰好发起一次向量检索");
        Map<String, Object> pairs = new LinkedHashMap<>();
        collect(captured.getFirst().getFilterExpression(), pairs);
        return pairs;
    }

    private static void collect(Filter.Operand operand, Map<String, Object> pairs) {
        if (operand instanceof Filter.Expression expression) {
            if (expression.type() == Filter.ExpressionType.EQ) {
                pairs.put(((Filter.Key) expression.left()).key(), ((Filter.Value) expression.right()).value());
                return;
            }
            Assertions.assertEquals(Filter.ExpressionType.AND, expression.type(),
                    "出现非 AND/EQ 节点，过滤语义被改写：" + expression);
            collect(expression.left(), pairs);
            collect(expression.right(), pairs);
            return;
        }
        Assertions.fail("叶子位置只能是 EQ 节点，实际是：" + operand);
    }

    private AgentKnowledgeRagService service(List<SearchRequest> captured) {
        VectorStore stubStore = new VectorStore() {
            @Override
            public void add(List<Document> documents) { }

            @Override
            public void delete(List<String> idList) { }

            @Override
            public void delete(Filter.Expression filterExpression) { }

            @Override
            public List<Document> similaritySearch(SearchRequest request) {
                captured.add(request);
                return List.of();
            }
        };
        return new AgentKnowledgeRagService(stubStore,
                new DashScopeRerankService("sk-test", false, "gte-rerank-v2", 1000));
    }
}
