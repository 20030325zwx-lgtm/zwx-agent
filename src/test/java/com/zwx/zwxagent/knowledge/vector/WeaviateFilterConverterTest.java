package com.zwx.zwxagent.knowledge.vector;

import io.weaviate.client6.v1.api.collections.query.Filter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.Filter.ExpressionType;
import org.springframework.ai.vectorstore.filter.Filter.Key;
import org.springframework.ai.vectorstore.filter.Filter.Value;

import java.util.List;

/**
 * Spring AI 过滤表达式树 → Weaviate 原生 where 的翻译面：
 * 只允许字面量值节点（与 pgvector 路径 FilterSafetyTest 同一安全约定），
 * 不支持的操作符/操作数直接抛异常（fail closed），绝不拼接字符串。
 */
class WeaviateFilterConverterTest {

    private static Expression eq(String key, Object value) {
        return new Expression(ExpressionType.EQ, new Key(key), new Value(value));
    }

    private static Expression and(Expression left, Expression right) {
        return new Expression(ExpressionType.AND, left, right);
    }

    @Test
    void eqStringBuildsNativePropertyFilter() {
        Filter filter = WeaviateFilterConverter.convert(eq("tenantId", "default"));
        // toString 走 client6 的 Filter 结构化渲染，不允许出现 SQL 拼接痕迹
        Assertions.assertTrue(filter.toString().contains("tenantId"), () -> "unexpected: " + filter);
        Assertions.assertTrue(filter.toString().contains("default"), () -> "unexpected: " + filter);
    }

    @Test
    void eqNumberAndBooleanUseTypedOperands() {
        Assertions.assertTrue(WeaviateFilterConverter.convert(eq("chunkIndex", 3)).toString().contains("chunkIndex"));
        Assertions.assertTrue(WeaviateFilterConverter.convert(eq("figureTruncated", Boolean.TRUE))
                .toString().contains("figureTruncated"));
    }

    @Test
    void andTreeIsFlattenedIntoOperands() {
        Expression tree = and(eq("tenantId", "t1"), and(eq("agentKey", "super"), eq("lifecycleStatus", "ACTIVE")));
        Filter filter = WeaviateFilterConverter.convert(tree);
        String rendered = filter.toString();
        Assertions.assertTrue(rendered.contains("tenantId"), rendered);
        Assertions.assertTrue(rendered.contains("agentKey"), rendered);
        Assertions.assertTrue(rendered.contains("lifecycleStatus"), rendered);
        Assertions.assertTrue(rendered.contains("And"), rendered);
    }

    @Test
    void inExpandsToArrayEquality() {
        Expression in = new Expression(ExpressionType.IN, new Key("agentKey"), new Value(List.of("love", "super")));
        Filter filter = WeaviateFilterConverter.convert(in);
        Assertions.assertTrue(filter.toString().contains("love"), () -> "unexpected: " + filter);
        Assertions.assertTrue(filter.toString().contains("super"), () -> "unexpected: " + filter);
    }

    @Test
    void rangeOperatorsRequireNumericValue() {
        Expression gte = new Expression(ExpressionType.GTE, new Key("versionNo"), new Value(2));
        Assertions.assertTrue(WeaviateFilterConverter.convert(gte).toString().contains("versionNo"));
        Expression gteText = new Expression(ExpressionType.GTE, new Key("versionNo"), new Value("2"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> WeaviateFilterConverter.convert(gteText),
                "字符串范围过滤应直接拒绝（fail closed）");
    }

    @Test
    void notWrapsNestedExpression() {
        Expression ne = new Expression(ExpressionType.EQ, new Key("lifecycleStatus"), new Value("ARCHIVED"));
        Expression not = new Expression(ExpressionType.NOT, ne);
        Assertions.assertTrue(WeaviateFilterConverter.convert(not).toString().contains("lifecycleStatus"));
    }

    @Test
    void bareKeyOperandIsRejected() {
        Expression bogus = new Expression(ExpressionType.EQ, new Key("tenantId"), new Key("tenantId"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> WeaviateFilterConverter.convert(bogus),
                "EQ 缺少值节点必须拒绝");
    }

    @Test
    void quotedValuesStayLiteral() {
        // 含引号/空格的值必须原样作为绑定值：转换层不做任何字符串拼装（注入回归，与 pg 路径同约定）
        Filter filter = WeaviateFilterConverter.convert(eq("tenantId", "t' OR agentKey!='x"));
        Assertions.assertTrue(filter.toString().contains("t' OR agentKey!='x"), () -> "unexpected: " + filter);
    }
}
