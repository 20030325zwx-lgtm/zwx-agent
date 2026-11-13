package com.zwx.zwxagent.knowledge.vector;

import io.weaviate.client6.v1.api.collections.Tokenization;

/**
 * Weaviate 连接与 Collection 配置（app.weaviate.*，application.yml 提供默认值，
 * 生产由 compose 环境变量覆盖；application-local.yml 不需要新增键）。
 *
 * <p>hybridAlpha：原生 Hybrid Search 的向量/BM25 权重（0.5 = 等权，对应迁移前 Java RRF
 * 双通道等贡献语义）；评测后可按 Recall/MRR 调整。</p>
 */
public record WeaviateSettings(
        String scheme,
        String host,
        int httpPort,
        int grpcPort,
        int timeoutSeconds,
        String collection,
        Tokenization bm25Tokenization,
        double hybridAlpha,
        int listLimit) {

    public static Tokenization parseTokenization(String value) {
        if (value == null || value.isBlank()) return Tokenization.GSE;
        try {
            return Tokenization.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return Tokenization.GSE;
        }
    }
}
