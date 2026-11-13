package com.zwx.zwxagent.knowledge.vector;

import io.micrometer.observation.ObservationRegistry;
import io.weaviate.client6.v1.api.WeaviateClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter.ExpressionType;
import org.springframework.ai.vectorstore.filter.Filter.Key;
import org.springframework.ai.vectorstore.filter.Filter.Value;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实 Ollama 嵌入 + 真实 Weaviate 的端到端集成测试（免费离线嵌入方案）：
 * 验证 qwen3-embedding:0.6b 的 1024 维向量能写入并驱动 nearVector / 原生 Hybrid / BM25 / 兄弟扩展，
 * 且换模型后集合维度随首条向量自动成立。
 * 本地需 ollama（含 qwen3-embedding:0.6b）与 aster-weaviate 容器；CI 自动跳过。
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class OllamaEmbeddingVectorStoreIntegrationTest {

    private static final String TENANT = "ollama-it";
    private static final String AGENT = "super";
    private static final String COLLECTION = "AgentKnowledgeChunkOllamaIt";
    private static final String OLLAMA_HOST = "127.0.0.1";
    private static final int OLLAMA_PORT = 11434;

    private static WeaviateKnowledgeVectorStore store;
    private static WeaviateClient adminClient;
    private static EmbeddingModel embeddingModel;

    @BeforeAll
    static void setUp() throws IOException {
        Assumptions.assumeTrue(portOpen(OLLAMA_HOST, OLLAMA_PORT), "本地 Ollama 未运行，跳过");
        Assumptions.assumeTrue(portOpen("127.0.0.1", 8080), "本地 Weaviate 未运行，跳过");
        embeddingModel = OllamaEmbeddingModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl("http://" + OLLAMA_HOST + ":" + OLLAMA_PORT).build())
                .defaultOptions(OllamaOptions.builder().model("qwen3-embedding:0.6b").build())
                .observationRegistry(ObservationRegistry.NOOP)
                .modelManagementOptions(ModelManagementOptions.defaults())
                .build();
        WeaviateSettings settings = new WeaviateSettings("http", "127.0.0.1", 8080, 50051, 30,
                COLLECTION, WeaviateSettings.parseTokenization("gse"), 0.5, 100);
        adminClient = WeaviateClient.connectToCustom(config -> config.scheme("http")
                .httpHost("127.0.0.1").httpPort(8080).grpcHost("127.0.0.1").grpcPort(50051));
        try {
            adminClient.collections.delete(COLLECTION);
        } catch (Exception ignored) {
        }
        store = new WeaviateKnowledgeVectorStore(embeddingModel, settings,
                new WeaviateSchemaManager(settings), new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @AfterAll
    static void tearDown() {
        if (adminClient != null) {
            try {
                adminClient.collections.delete(COLLECTION);
            } catch (Exception ignored) {
            }
            try {
                adminClient.close();
            } catch (Exception ignored) {
            }
        }
        if (store != null) store.close();
    }

    @Test
    void qwen3EmbeddingDrivesVectorAndHybridRetrieval() {
        // 1) 模型维度：qwen3-embedding:0.6b 为 1024 维
        float[] probe = embeddingModel.embed("高速公路交通事故应急处置");
        Assertions.assertEquals(1024, probe.length, "qwen3-embedding:0.6b 应为 1024 维");

        // 2) 写入真实嵌入（近义改写的查询应靠语义命中，关键词不完全一致）
        store.add(List.of(
                chunk("ollama-doc|chunk|1", "高速公路交通事故应急处置流程总则", 1, "第一章 > 处置总则", "TEXT"),
                chunk("ollama-doc|chunk|2", "G60 沪昆高速拥堵应联系路政与监控中心", 2, "第一章 > 处置总则", "TEXT"),
                chunk("ollama-doc|chunk|3", "收费站设备故障的应急处理步骤", 3, "第二章 > 设备故障", "TABLE")));

        // 3) 向量检索：用近义改写查询（"撞车事故怎么处理" 与 "交通事故应急处置" 词面不同）
        List<Document> vectorHits = store.similaritySearch(SearchRequest.builder()
                .query("发生撞车事故后应该怎么处置")
                .topK(3).similarityThreshold(0.0)
                .filterExpression(scopeFilter())
                .build());
        Assertions.assertFalse(vectorHits.isEmpty(), "Ollama 真实向量应命中语义相关切片");
        Assertions.assertNotNull(vectorHits.getFirst().getScore(), "必须返回相似度分");
        Assertions.assertEquals(TENANT, String.valueOf(vectorHits.getFirst().getMetadata().get("tenantId")));

        // 4) 原生 Hybrid（向量 + BM25 融合）：关键词精确查询
        List<Document> fused = store.hybridSearch(TENANT, AGENT, "沪昆高速拥堵", 10, 0.0);
        Assertions.assertFalse(fused.isEmpty(), "原生 hybrid 应返回融合候选");
        Assertions.assertNotNull(fused.getFirst().getScore(), "融合候选应带 RRF 分");

        // 5) BM25（gse）中文命中
        List<Document> lexical = store.bm25Search(TENANT, AGENT, "沪昆高速", 5);
        Assertions.assertFalse(lexical.isEmpty(), "BM25 应命中「沪昆高速」");

        // 6) 兄弟扩展阅读序
        List<Document> siblings = store.fetchSiblings(TENANT, AGENT, "ollama-doc", "第一章 > 处置总则", 5);
        Assertions.assertEquals(2, siblings.size());
        Assertions.assertEquals(uuid("ollama-doc|chunk|1"), siblings.get(0).getId());

        // 7) 清理
        store.deleteByDocumentId("ollama-doc", TENANT, AGENT);
        Assertions.assertTrue(store.listByDocument(TENANT, AGENT, "ollama-doc").isEmpty());
    }

    private static org.springframework.ai.vectorstore.filter.Filter.Expression scopeFilter() {
        return new org.springframework.ai.vectorstore.filter.Filter.Expression(ExpressionType.AND,
                new org.springframework.ai.vectorstore.filter.Filter.Expression(ExpressionType.EQ,
                        new Key("tenantId"), new Value(TENANT)),
                new org.springframework.ai.vectorstore.filter.Filter.Expression(ExpressionType.EQ,
                        new Key("agentKey"), new Value(AGENT)));
    }

    private static String uuid(String seed) {
        return java.util.UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static Document chunk(String seedId, String text, int chunkIndex, String sectionPath, String sourceType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tenantId", TENANT);
        metadata.put("agentKey", AGENT);
        metadata.put("documentId", "ollama-doc");
        metadata.put("lifecycleStatus", "ACTIVE");
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("sectionPath", sectionPath);
        metadata.put("sourceType", sourceType);
        metadata.put("versionNo", 1);
        return Document.builder().id(uuid(seedId)).text(text).metadata(metadata).build();
    }

    private static boolean portOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }
}
