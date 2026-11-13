package com.zwx.zwxagent.knowledge.vector;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Optional;

/**
 * 知识向量存储端口：在 Spring AI {@link VectorStore}（add/delete/similaritySearch）之上，
 * 收敛原先散落在业务层直查 {@code agent_knowledge_vector} 表的向量操作，使
 * pgvector（桌面端/CI）与 Weaviate（服务端/本地开发）可按 {@code app.vector-store.provider} 互换。
 *
 * <p>扩展方法带默认空实现：单测的匿名 stub / Mockito mock 只需实现 Spring AI 三个基础方法；
 * 生产实现（Pg/Weaviate）必须全部覆写。</p>
 *
 * <p>过滤器约定沿用 Spring AI {@link org.springframework.ai.vectorstore.filter.Filter.Expression}
 * 表达式树（值恒为字面量节点，由各实现转换为绑定参数/原生过滤），不允许调用方拼接过滤字符串。</p>
 */
public interface KnowledgeVectorStore extends VectorStore {

    /**
     * 词法通道召回：按关键词相关度返回 scope 内 ACTIVE 切片。
     * query 为原始查询串；pgvector 实现内部做中文 2-gram + 英文分词（见 {@link LexicalTerms}），
     * Weaviate 实现走 BM25（gse 分词）。
     */
    default List<Document> bm25Search(String tenantId, String agentKey, String query, int limit) {
        return List.of();
    }

    /** 兄弟块扩展：scope 内同文档同章节的 ACTIVE 切片，按 chunkIndex 阅读序返回至多 limit 条。 */
    default List<Document> fetchSiblings(String tenantId, String agentKey, String documentId,
                                         String sectionPath, int limit) {
        return List.of();
    }

    /** 文档切片清单（详情页）：scope 内该文档全部切片，按 chunkIndex 阅读序。 */
    default List<Document> listByDocument(String tenantId, String agentKey, String documentId) {
        return List.of();
    }

    /** 按切片 ID 读取（引用预览）；scope 校验由调用方完成。 */
    default Optional<Document> findById(String tenantId, String agentKey, String chunkId) {
        return Optional.empty();
    }

    /**
     * 混合召回（融合通道）：向量 + 关键词双路融合后的候选池，替代迁移前 Java 侧 RRF。
     * Weaviate 实现走原生 Hybrid Search（FusionType.RANKED，即服务端 RRF k=60，alpha 可配）；
     * pgvector 实现为双路查询后按同一公式本地融合。返回 Document.score = 融合分（RRF 尺度一致）。
     *
     * @param vectorSimilarityThreshold 向量通道相似度阈值（similarity = 1 - cosine distance）；
     *                                  pg 实现用于预筛向量池，Weaviate 原生 hybrid 无逐对象阈值语义、忽略该参数
     */
    default List<Document> hybridSearch(String tenantId, String agentKey, String query,
                                        int limit, double vectorSimilarityThreshold) {
        return List.of();
    }

    /** 文档生命周期批量翻转（publish/reject）：按 documentId 定位全部切片，与旧 SQL 语义一致。 */
    default void updateLifecycleStatus(String documentId, String lifecycleStatus) {
    }

    /** 删除文档全部向量（彻底删除场景，与旧 SQL 一致仅按 documentId 匹配）。 */
    default void deleteByDocumentId(String documentId) {
    }

    /** 删除文档全部向量（重建索引场景，按 scope 收紧匹配）。 */
    default void deleteByDocumentId(String documentId, String tenantId, String agentKey) {
    }
}
