package com.zwx.zwxagent.knowledge.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * pgvector 实现（桌面端 / CI / 回滚路径）：包装 Spring AI {@code PgVectorStore}（向量 CRUD + ANN 检索），
 * 并承接原先散落在业务层的直查 SQL（词法召回、兄弟扩展、切片清单、生命周期翻转、按文档删除）。
 * SQL 与迁移前逐字一致，保证 {@code app.vector-store.provider=pgvector} 时行为零变化。
 */
public class PgKnowledgeVectorStore implements KnowledgeVectorStore {

    private static final String VECTOR_TABLE = "agent_knowledge_vector";

    private final VectorStore pgVectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PgKnowledgeVectorStore(VectorStore pgVectorStore, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.pgVectorStore = pgVectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void add(List<Document> documents) {
        pgVectorStore.add(documents);
    }

    @Override
    public void delete(List<String> idList) {
        pgVectorStore.delete(idList);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        pgVectorStore.delete(filterExpression);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return pgVectorStore.similaritySearch(request);
    }

    @Override
    public List<Document> bm25Search(String tenantId, String agentKey, String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        List<String> terms = LexicalTerms.extract(query);
        if (terms.isEmpty()) return List.of();
        StringBuilder scoreExpression = new StringBuilder();
        StringBuilder likePredicate = new StringBuilder();
        List<Object> arguments = new ArrayList<>();
        for (String term : terms) {
            if (!scoreExpression.isEmpty()) scoreExpression.append(" + ");
            scoreExpression.append("(CASE WHEN content ILIKE ? THEN 1 ELSE 0 END)");
            if (!likePredicate.isEmpty()) likePredicate.append(" OR ");
            likePredicate.append("content ILIKE ?");
            arguments.add("%" + term + "%");
        }
        // ILIKE 必须出现在内层 WHERE：content 上的 trgm GIN 才能作为索引条件参与规划；
        // 旧写法把 ILIKE 藏在 SELECT 的 CASE 里、外层过滤 lexical_score > 0，任何索引都够不着（评审 #1）。
        for (String term : terms) arguments.add("%" + term + "%");
        arguments.add(tenantId);
        arguments.add(agentKey);
        arguments.add(limit);
        // 平局按短内容优先：命中词数打分对长切片天然占优，短文更可能是聚焦答案。
        String sql = """
                SELECT id::text, content, metadata, %s AS lexical_score
                FROM %s
                WHERE metadata ->> 'tenantId' = ? AND metadata ->> 'agentKey' = ?
                  AND metadata ->> 'lifecycleStatus' = 'ACTIVE'
                  AND (%s)
                ORDER BY lexical_score DESC, length(content), id
                LIMIT ?
                """.formatted(scoreExpression, VECTOR_TABLE, likePredicate);
        return jdbcTemplate.query(sql, documentRowMapper(), arguments.toArray())
                .stream().filter(java.util.Objects::nonNull).toList();
    }

    /**
     * 服务端混合召回的 pg 等价实现：阈值预筛的向量池 + 词法池，按 RRF（k=60）本地融合，
     * 与 Weaviate 原生 Hybrid（FusionType.RANKED）同公式同尺度。
     */
    @Override
    public List<Document> hybridSearch(String tenantId, String agentKey, String query,
                                       int limit, double vectorSimilarityThreshold) {
        List<Document> vectorPool = similaritySearch(SearchRequest.builder()
                .query(query == null ? "" : query).topK(limit)
                .similarityThreshold(vectorSimilarityThreshold)
                .filterExpression(scopeAndLifecycle(tenantId, agentKey)).build());
        List<Document> lexicalPool = bm25Search(tenantId, agentKey, query, limit);
        return ReciprocalRankFusion.fuse(vectorPool, lexicalPool);
    }

    private static Filter.Expression scopeAndLifecycle(String tenantId, String agentKey) {
        var builder = new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder();
        return builder.and(
                builder.and(builder.eq("tenantId", tenantId), builder.eq("agentKey", agentKey)),
                builder.eq("lifecycleStatus", "ACTIVE")).build();
    }

    @Override
    public List<Document> fetchSiblings(String tenantId, String agentKey, String documentId,
                                        String sectionPath, int limit) {
        List<Object> args = new ArrayList<>(List.of(tenantId, agentKey, documentId, sectionPath, limit));
        return jdbcTemplate.query("""
                SELECT id::text, content, metadata FROM %s
                WHERE metadata ->> 'tenantId' = ? AND metadata ->> 'agentKey' = ?
                  AND metadata ->> 'lifecycleStatus' = 'ACTIVE'
                  AND metadata ->> 'documentId' = ? AND metadata ->> 'sectionPath' = ?
                ORDER BY NULLIF(metadata ->> 'chunkIndex', '')::integer NULLS LAST, id
                LIMIT ?
                """.formatted(VECTOR_TABLE), documentRowMapper(), args.toArray())
                .stream().filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public List<Document> listByDocument(String tenantId, String agentKey, String documentId) {        return jdbcTemplate.query("""
                SELECT id::text, content, metadata FROM %s
                WHERE metadata ->> 'documentId' = ? AND metadata ->> 'tenantId' = ? AND metadata ->> 'agentKey' = ?
                ORDER BY NULLIF(metadata ->> 'chunkIndex', '')::integer NULLS LAST, id
                """.formatted(VECTOR_TABLE), documentRowMapper(), documentId, tenantId, agentKey)
                .stream().filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public Optional<Document> findById(String tenantId, String agentKey, String chunkId) {
        return jdbcTemplate.query("""
                SELECT id::text, content, metadata FROM %s
                WHERE id::text = ? AND metadata ->> 'tenantId' = ? AND metadata ->> 'agentKey' = ?
                """.formatted(VECTOR_TABLE), documentRowMapper(), chunkId, tenantId, agentKey)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    @Override
    public void updateLifecycleStatus(String documentId, String lifecycleStatus) {
        jdbcTemplate.update("""
                UPDATE %s
                SET metadata = (metadata::jsonb || jsonb_build_object('lifecycleStatus', ?))::json
                WHERE metadata ->> 'documentId' = ?
                  AND metadata ->> 'lifecycleStatus' IS DISTINCT FROM ?
                """.formatted(VECTOR_TABLE), lifecycleStatus, documentId, lifecycleStatus);
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        List<String> chunkIds = jdbcTemplate.queryForList(
                "SELECT id::text FROM %s WHERE metadata ->> 'documentId' = ?".formatted(VECTOR_TABLE),
                String.class, documentId);
        if (!chunkIds.isEmpty()) pgVectorStore.delete(chunkIds);
    }

    @Override
    public void deleteByDocumentId(String documentId, String tenantId, String agentKey) {
        List<String> chunkIds = jdbcTemplate.queryForList("""
                SELECT id::text FROM %s
                WHERE metadata ->> 'documentId' = ? AND metadata ->> 'tenantId' = ? AND metadata ->> 'agentKey' = ?
                """.formatted(VECTOR_TABLE), String.class, documentId, tenantId, agentKey);
        if (!chunkIds.isEmpty()) pgVectorStore.delete(chunkIds);
    }

    private RowMapper<Document> documentRowMapper() {
        return (rs, row) -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = objectMapper.readValue(rs.getString("metadata"), Map.class);
                return Document.builder().id(rs.getString("id")).text(rs.getString("content")).metadata(metadata).build();
            } catch (Exception exception) {
                return null;
            }
        };
    }
}
