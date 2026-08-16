package com.zwx.zwxagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 恋爱大师向量数据库配置。
 */
@Configuration
public class LoveAppVectorStoreConfig {

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

    @Resource
    private MyTokenTextSplitter myTokenTextSplitter;

    @Resource
    @Bean("loveAppVectorStore")
    VectorStore loveAppVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel dashscopeEmbeddingModel) {
        PgVectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(dashscopeEmbeddingModel.dimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .vectorTableName("love_knowledge_vector")
                .build();
        vectorStore.afterPropertiesSet();
        List<Document> documentList = loveAppDocumentLoader.loadMarkdowns();
        List<Document> chunks = myTokenTextSplitter.splitCustomized(documentList).stream()
                .map(document -> document.mutate()
                        .id(UUID.nameUUIDFromBytes((document.getMetadata().get("objectKey") + "|" + document.getText())
                                .getBytes(StandardCharsets.UTF_8)).toString())
                        .build())
                .toList();
        vectorStore.delete(chunks.stream().map(Document::getId).toList());
        for (int start = 0; start < chunks.size(); start += 25) {
            vectorStore.add(chunks.subList(start, Math.min(start + 25, chunks.size())));
        }
        return vectorStore;
    }
}
