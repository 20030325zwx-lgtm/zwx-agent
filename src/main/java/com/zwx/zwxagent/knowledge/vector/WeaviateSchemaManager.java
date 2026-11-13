package com.zwx.zwxagent.knowledge.vector;

import io.weaviate.client6.v1.api.collections.CollectionConfig;
import io.weaviate.client6.v1.api.collections.DataType;
import io.weaviate.client6.v1.api.collections.Property;
import io.weaviate.client6.v1.api.collections.Tokenization;
import io.weaviate.client6.v1.api.collections.VectorConfig;
import io.weaviate.client6.v1.api.collections.VectorIndex;
import io.weaviate.client6.v1.api.collections.vectorindex.Distance;
import io.weaviate.client6.v1.api.collections.vectorindex.Hnsw;
import io.weaviate.client6.v1.api.WeaviateClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 幂等管理知识切片 Collection：不存在则按固定 schema 创建（无 vectorizer、应用侧传向量、
 * HNSW + cosine、text 属性按配置分词做 BM25）。属性集是检索/过滤/预览消费面的白名单，
 * 元数据中的其余键在写入侧收进 extraMeta（JSON 文本），读取侧再展开回 metadata。
 */
public class WeaviateSchemaManager {

    private static final Logger log = LoggerFactory.getLogger(WeaviateSchemaManager.class);

    private final WeaviateSettings settings;
    private volatile boolean ensured;

    public WeaviateSchemaManager(WeaviateSettings settings) {
        this.settings = settings;
    }

    /** 幂等确保 Collection 存在；失败抛 RuntimeException，由调用方决定降级策略。 */
    public void ensureCollection(WeaviateClient client) throws IOException {
        if (ensured) return;
        synchronized (this) {
            if (ensured) return;
            if (client.collections.exists(settings.collection())) {
                ensured = true;
                return;
            }
            long startedAt = System.currentTimeMillis();
            client.collections.create(config());
            ensured = true;
            log.info("Weaviate collection created: name={}, tokenization={}, took={}ms",
                    settings.collection(), settings.bm25Tokenization(), System.currentTimeMillis() - startedAt);
        }
    }

    /** 供健康检查/运维诊断读取。 */
    public WeaviateSettings settings() {
        return settings;
    }

    private CollectionConfig config() {
        Tokenization tokenization = settings.bm25Tokenization();
        return CollectionConfig.of(settings.collection(), c -> c
                .description("知识库切片向量（2026-11 迁移自 pgvector agent_knowledge_vector）")
                .properties(
                        // 正文：BM25 检索面，中文按 gse 分词（词法通道）；不参与等值过滤
                        Property.text("text", p -> p.tokenization(tokenization).indexFilterable(false)
                                .description("切片正文（含表格行 HTML/图纸描述）")),
                        // 过滤面元数据：等值/排序用途，不进入 BM25 打分
                        Property.text("tenantId", p -> p.indexSearchable(false)),
                        Property.text("agentKey", p -> p.indexSearchable(false)),
                        Property.text("lifecycleStatus", p -> p.indexSearchable(false)),
                        Property.text("documentId", p -> p.indexSearchable(false)),
                        Property.text("parentDocumentId", p -> p.indexSearchable(false)),
                        Property.text("logicalKey", p -> p.indexSearchable(false)),
                        Property.text("parentLogicalKey", p -> p.indexSearchable(false)),
                        Property.text("sectionPath", p -> p.indexSearchable(false)),
                        Property.text("sourceType", p -> p.indexSearchable(false)),
                        Property.text("blockId", p -> p.indexSearchable(false)),
                        Property.text("filename", p -> p.indexSearchable(false)),
                        Property.text("objectKey", p -> p.indexSearchable(false)),
                        Property.text("regionLabel", p -> p.indexSearchable(false)),
                        Property.text("tableCaption", p -> p.indexSearchable(false)),
                        Property.text("caption", p -> p.indexSearchable(false)),
                        Property.text("bbox", p -> p.indexSearchable(false)),
                        Property.text("figureImages", p -> p.indexSearchable(false)),
                        Property.text("figureCaption", p -> p.indexSearchable(false)),
                        Property.text("figureType", p -> p.indexSearchable(false)),
                        Property.text("parseQuality", p -> p.indexSearchable(false)),
                        Property.text("chunking", p -> p.indexSearchable(false)),
                        Property.text("structuredContentType", p -> p.indexSearchable(false)),
                        Property.text("parser", p -> p.indexSearchable(false)),
                        Property.text("documentType", p -> p.indexSearchable(false)),
                        Property.text("chunkRole", p -> p.indexSearchable(false)),
                        // 其余低频元数据（headings/pageNumbers/docItems 等）统一进 JSON 文本
                        Property.text("extraMeta", p -> p.indexSearchable(false).indexFilterable(false)),
                        // 排序/范围过滤面：int 属性
                        Property.integer("chunkIndex"),
                        Property.integer("pageNumber"),
                        Property.integer("rowStart"),
                        Property.integer("rowEnd"),
                        Property.integer("versionNo"),
                        Property.integer("figureFragments"),
                        Property.integer("chunkSchemaVersion"),
                        Property.integer("previousChunkIndex"),
                        Property.integer("doclingChunkIndex"),
                        Property.integer("tokenCount"),
                        Property.bool("figureTruncated"))
                // 应用侧（DashScope）向量：无 vectorizer，HNSW + cosine 与 pgvector 语义对齐
                .vectorConfig(VectorConfig.selfProvided(VectorIndex.DEFAULT_VECTOR_NAME,
                        v -> v.vectorIndex(Hnsw.of(h -> h.distance(Distance.COSINE))))));
    }
}
