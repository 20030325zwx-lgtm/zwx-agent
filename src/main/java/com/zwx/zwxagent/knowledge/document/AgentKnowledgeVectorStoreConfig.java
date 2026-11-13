package com.zwx.zwxagent.knowledge.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.knowledge.vector.KnowledgeVectorStore;
import com.zwx.zwxagent.knowledge.vector.PgKnowledgeVectorStore;
import com.zwx.zwxagent.knowledge.vector.WeaviateKnowledgeVectorStore;
import com.zwx.zwxagent.knowledge.vector.WeaviateSchemaManager;
import com.zwx.zwxagent.knowledge.vector.WeaviateSettings;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 知识向量存储装配：按 {@code app.vector-store.provider} 选择实现。
 * <ul>
 *   <li>{@code weaviate}（默认）：服务端 / 本地开发，原生 Hybrid Search + BM25；</li>
 *   <li>{@code pgvector}：桌面端（内置 pgserver）/ CI / 回滚路径，行为与迁移前逐字一致。</li>
 * </ul>
 * bean 名保持 {@code agentKnowledgeVectorStore}，业务层只依赖 {@link KnowledgeVectorStore} 端口。
 */
@Configuration
public class AgentKnowledgeVectorStoreConfig {

    @Bean("agentKnowledgeVectorStore")
    KnowledgeVectorStore agentKnowledgeVectorStore(
            JdbcTemplate jdbcTemplate,
            ApplicationContext applicationContext,
            ObjectMapper objectMapper,
            @Value("${app.vector-store.provider:weaviate}") String provider,
            @Value("${app.embedding.provider:dashscope}") String embeddingProvider,
            @Value("${app.weaviate.scheme:http}") String scheme,
            @Value("${app.weaviate.host:127.0.0.1}") String host,
            @Value("${app.weaviate.http-port:8080}") int httpPort,
            @Value("${app.weaviate.grpc-port:50051}") int grpcPort,
            @Value("${app.weaviate.timeout-seconds:15}") int timeoutSeconds,
            @Value("${app.weaviate.collection:AgentKnowledgeChunk}") String collection,
            @Value("${app.weaviate.bm25-tokenization:gse}") String bm25Tokenization,
            @Value("${app.weaviate.hybrid-alpha:0.5}") double hybridAlpha,
            @Value("${app.weaviate.list-limit:10000}") int listLimit) {
        EmbeddingModel embeddingModel = resolveEmbeddingModel(applicationContext, embeddingProvider);
        if ("pgvector".equalsIgnoreCase(provider == null ? "" : provider.trim())) {
            return new PgKnowledgeVectorStore(buildPgVectorStore(jdbcTemplate, embeddingModel),
                    jdbcTemplate, objectMapper);
        }
        WeaviateSettings settings = new WeaviateSettings(scheme, host, httpPort, grpcPort, timeoutSeconds,
                collection, WeaviateSettings.parseTokenization(bm25Tokenization), hybridAlpha, listLimit);
        return new WeaviateKnowledgeVectorStore(embeddingModel, settings,
                new WeaviateSchemaManager(settings), objectMapper);
    }

    /**
     * 按 provider 显式解析嵌入模型 bean：DashScope 与 Ollama 的自动配置 bean 都带 @Primary，
     * 按类型注入会命中「多个 primary」歧义（2026-11-13 实测），因此按自动配置的固定 bean 名取：
     * dashscope → dashscopeEmbeddingModel；ollama → ollamaEmbeddingModel（模型由
     * spring.ai.ollama.embedding.model 指定，见 application.yml）。
     */
    private EmbeddingModel resolveEmbeddingModel(ApplicationContext applicationContext, String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        String beanName = "ollama".equals(normalized) ? "ollamaEmbeddingModel" : "dashscopeEmbeddingModel";
        EmbeddingModel model = applicationContext.getBean(beanName, EmbeddingModel.class);
        org.slf4j.LoggerFactory.getLogger(AgentKnowledgeVectorStoreConfig.class)
                .info("知识向量嵌入模型：provider={}, bean={}", beanName, model.getClass().getSimpleName());
        return model;
    }

    private PgVectorStore buildPgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        PgVectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                // Do not call the remote embedding API while the application is starting.
                .dimensions(1536)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .vectorTableName("agent_knowledge_vector")
                .build();
        vectorStore.afterPropertiesSet();
        return vectorStore;
    }
}
