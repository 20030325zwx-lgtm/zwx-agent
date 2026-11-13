package com.zwx.zwxagent.knowledge.vector;

import io.weaviate.client6.v1.api.collections.query.Filter;

import java.util.List;

import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.vectorstore.filter.Filter.ExpressionType;
import org.springframework.ai.vectorstore.filter.Filter.Key;
import org.springframework.ai.vectorstore.filter.Filter.Operand;
import org.springframework.ai.vectorstore.filter.Filter.Value;

/**
 * Spring AI 过滤表达式树 → Weaviate 原生 where 过滤。
 * 与 pgvector 路径同一约定：值恒为字面量节点（FilterSafetyTest 回归保障），键名即 Collection 属性名；
 * 任何超出支持面的类型直接抛异常（fail closed），不做静默字符串拼接。
 */
public final class WeaviateFilterConverter {

    private WeaviateFilterConverter() {
    }

    public static Filter convert(Expression expression) {
        ExpressionType type = expression.type();
        return switch (type) {
            case AND -> Filter.and(convert(asExpression(expression.left())), convert(asExpression(expression.right())));
            case OR -> Filter.or(convert(asExpression(expression.left())), convert(asExpression(expression.right())));
            case NOT -> Filter.not(convert(asExpression(expression.left())));
            case EQ, NE, GT, GTE, LT, LTE, IN, NIN -> comparison(type, expression);
        };
    }

    /** Spring AI 的 AND/OR/NOT 操作数静态类型是 Operand；逻辑位置只能是表达式，否则拒绝（fail closed）。 */
    private static Expression asExpression(Operand operand) {
        if (operand instanceof Expression expression) return expression;
        throw new IllegalArgumentException("Logical filter operand must be an expression, got: " + operand);
    }

    private static Filter comparison(ExpressionType type, Expression expression) {
        Key key = expression.left() instanceof Key k ? k
                : expression.right() instanceof Key k ? k
                : null;
        Value value = expression.left() instanceof Value v ? v
                : expression.right() instanceof Value v ? v
                : null;
        if (key == null || value == null) {
            throw new IllegalArgumentException("Unsupported filter expression: " + type + " " + expression);
        }
        Filter.FilterBuilder property = Filter.property(key.key());
        Object raw = value.value();
        return switch (type) {
            case EQ -> eq(property, raw);
            case NE -> ne(property, raw);
            case IN -> in(property, asList(raw), false);
            case NIN -> in(property, asList(raw), true);
            case GT, GTE, LT, LTE -> range(property, type, raw);
            default -> throw new IllegalArgumentException("Unsupported filter operator: " + type);
        };
    }

    private static Filter eq(Filter.FilterBuilder property, Object value) {
        if (value instanceof Number number) return property.eq(number.longValue());
        if (value instanceof Boolean bool) return property.eq(bool);
        return property.eq(String.valueOf(value));
    }

    private static Filter ne(Filter.FilterBuilder property, Object value) {
        if (value instanceof Number number) return property.ne(number.longValue());
        if (value instanceof Boolean bool) return property.ne(bool);
        return property.ne(String.valueOf(value));
    }

    /** IN/NIN：Weaviate 文本/数值数组等值即集合语义（eq(String...) / ne(String...)）。 */
    private static Filter in(Filter.FilterBuilder property, List<?> values, boolean negate) {
        if (values.isEmpty()) throw new IllegalArgumentException("IN filter requires at least one value");
        boolean allNumbers = values.stream().allMatch(v -> v instanceof Number);
        if (allNumbers) {
            Long[] converted = values.stream().map(v -> ((Number) v).longValue()).toArray(Long[]::new);
            return negate ? property.ne(converted) : property.eq(converted);
        }
        String[] converted = values.stream().map(String::valueOf).toArray(String[]::new);
        return negate ? property.ne(converted) : property.eq(converted);
    }

    private static Filter range(Filter.FilterBuilder property, ExpressionType type, Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Range filter requires a numeric value: " + type + " " + value);
        }
        long v = number.longValue();
        return switch (type) {
            case GT -> property.gt(v);
            case GTE -> property.gte(v);
            case LT -> property.lt(v);
            case LTE -> property.lte(v);
            default -> throw new IllegalArgumentException("Unsupported range operator: " + type);
        };
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of(value);
    }

}
