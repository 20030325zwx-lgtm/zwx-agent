package com.zwx.zwxagent.knowledge.vector;

import io.weaviate.client6.v1.api.WeaviateClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Weaviate 真实集成测试（本地 aster-weaviate 容器，http://127.0.0.1:8080）：
 * 用确定性 stub embedding（字符哈希向量，语义近似）替代 DashScope，验证 client6 全链路——
 * 写入（含 v3 确定性 UUID）、nearVector 过滤检索、原生 Hybrid（RANKED）、BM25（gse）、
 * chunkIndex 排序取回、生命周期 PATCH、按过滤删除。
 * CI 无容器自动跳过；DashScope 欠费不影响本测试（embedding 全部本地伪造）。
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class WeaviateKnowledgeVectorStoreIntegrationTest {

    private static final String TENANT = "it-tenant";
    private static final String AGENT = "super";
    private static final String COLLECTION = "AgentKnowledgeChunkIt";
    private static final String HOST = "127.0.0.1";
    private static final int HTTP_PORT = 8080;
    private static final int GRPC_PORT = 50051;

    private static WeaviateKnowledgeVectorStore store;
    private static WeaviateClient adminClient;

    /** 字符级哈希向量（32 维、归一化）：共享字符越多越相似，足以驱动 nearVector/排序断言。 */
    private static final EmbeddingModel HASH_EMBEDDING = new EmbeddingModel() {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<float[]> vectors = new ArrayList<>();
            for (String instruction : request.getInstructions()) {
                vectors.add(hashVector(instruction));
            }
            return new EmbeddingResponse(vectors.stream()
                    .map(v -> new org.springframework.ai.embedding.Embedding(v, 0))
                    .toList());
        }

        @Override
        public float[] embed(Document document) {
            return hashVector(document.getFormattedContent());
        }
    };

    private static float[] hashVector(String text) {
        float[] vector = new float[32];
        String normalized = text == null ? "" : text.toLowerCase();
        for (int i = 0; i < normalized.length(); i++) {
            int dim = (normalized.charAt(i) * 31 + i) % 32;
            vector[dim] += 1.0f;
        }
        double norm = 0;
        for (float v : vector) norm += v * v;
        norm = Math.sqrt(norm);
        for (int d = 0; d < vector.length; d++) vector[d] = norm == 0 ? 0 : (float) (vector[d] / norm);
        return vector;
    }

    @BeforeAll
    static void setUp() throws IOException {
        Assumptions.assumeTrue(reachable(), "本地 Weaviate 未运行（docker run aster-weaviate），跳过集成测试");
        WeaviateSettings settings = new WeaviateSettings("http", HOST, HTTP_PORT, GRPC_PORT, 10,
                COLLECTION, WeaviateSettings.parseTokenization("gse"), 0.5, 100);
        adminClient = WeaviateClient.connectToCustom(config -> config.scheme("http")
                .httpHost(HOST).httpPort(HTTP_PORT).grpcHost(HOST).grpcPort(GRPC_PORT));
        try {
            adminClient.collections.delete(COLLECTION);
        } catch (Exception ignored) {
        }
        store = new WeaviateKnowledgeVectorStore(HASH_EMBEDDING, settings,
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
    void endToEndChunkLifecycle() {
        // 1) 写入：v3 确定性 UUID + 白名单/extraMeta 双路元数据
        List<Document> chunks = List.of(
                chunk("it-doc|chunk|1", "高速公路交通事故应急处置流程总则", 1, "第一章 > 处置总则", "TEXT"),
                chunk("it-doc|chunk|2", "G60 沪昆高速拥堵应联系路政与监控中心", 2, "第一章 > 处置总则", "TEXT"),
                chunk("it-doc|chunk|3", "收费站设备故障的应急处理步骤", 3, "第二章 > 设备故障", "TABLE"));
        store.add(chunks);

        // 2) 向量检索：租户/agent/ACTIVE 服务端过滤 + cosine 相似度
        List<Document> vectorHits = store.similaritySearch(org.springframework.ai.vectorstore.SearchRequest.builder()
                .query("交通事故应急处置").topK(3).similarityThreshold(0.0)
                .filterExpression(new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder()
                        .and(new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder()
                                        .eq("tenantId", TENANT),
                                new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder()
                                        .eq("agentKey", AGENT)).build()).build());
        Assertions.assertFalse(vectorHits.isEmpty(), "nearVector 应命中已写入切片");
        Assertions.assertNotNull(vectorHits.getFirst().getScore(), "必须返回相似度分（1 - cosine distance）");
        Assertions.assertEquals(TENANT, String.valueOf(vectorHits.getFirst().getMetadata().get("tenantId")));

        // 3) 原生 Hybrid Search：RANKED（RRF k=60）+ alpha
        List<Document> fused = store.hybridSearch(TENANT, AGENT, "交通事故应急处置流程", 10, 0.4);
        Assertions.assertFalse(fused.isEmpty(), "原生 hybrid 应返回融合候选池");
        Assertions.assertNotNull(fused.getFirst().getScore(), "融合候选必须带 RRF 融合分");

        // 4) BM25（gse 中文分词）：专有名词精确命中
        List<Document> lexical = store.bm25Search(TENANT, AGENT, "沪昆高速", 5);
        Assertions.assertFalse(lexical.isEmpty(), "BM25 应按 gse 分词命中「沪昆高速」");

        // 5) 兄弟扩展：同文档同章节按 chunkIndex 阅读序
        List<Document> siblings = store.fetchSiblings(TENANT, AGENT, "it-doc", "第一章 > 处置总则", 5);
        Assertions.assertEquals(2, siblings.size(), "同章节应有 2 个兄弟块");
        Assertions.assertEquals(uuid("it-doc|chunk|1"), siblings.get(0).getId(), "chunkIndex 阅读序");
        Assertions.assertEquals(uuid("it-doc|chunk|2"), siblings.get(1).getId());

        // 6) 引用预览：按 ID 直取（scope 校验 + 元数据往返）
        Document preview = store.findById(TENANT, AGENT, WeaviateUuid.of(uuid("it-doc|chunk|3"))).orElseThrow();
        Assertions.assertEquals("TABLE", String.valueOf(preview.getMetadata().get("sourceType")));
        Assertions.assertTrue(preview.getMetadata().containsKey("headings"),
                "extraMeta 中的非白名单元数据应展开回 metadata");
        Assertions.assertTrue(store.findById(TENANT, AGENT + "-other", WeaviateUuid.of(uuid("it-doc|chunk|3"))).isEmpty(),
                "scope 不匹配必须读不到（租户隔离）");

        // 7) 生命周期翻转：publish 场景逐对象 PATCH
        store.updateLifecycleStatus("it-doc", "ARCHIVED");
        Assertions.assertTrue(store.bm25Search(TENANT, AGENT, "沪昆高速", 5).isEmpty(),
                "翻转后 lifecycleStatus=ARCHIVED 不应再命中 ACTIVE 词法通道");

        // 8) 按文档删除（重建索引路径，scope 收紧）
        store.deleteByDocumentId("it-doc", TENANT, AGENT);
        Assertions.assertTrue(store.listByDocument(TENANT, AGENT, "it-doc").isEmpty(), "删除后清单应为空");
    }

    private static String uuid(String seed) {
        return java.util.UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static Document chunk(String seedId, String text, int chunkIndex, String sectionPath, String sourceType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tenantId", TENANT);
        metadata.put("agentKey", AGENT);
        metadata.put("documentId", "it-doc");
        metadata.put("lifecycleStatus", "ACTIVE");
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("sectionPath", sectionPath);
        metadata.put("sourceType", sourceType);
        metadata.put("versionNo", 1);
        metadata.put("headings", List.of("第一章", "处置总则"));
        return Document.builder().id(uuid(seedId)).text(text).metadata(metadata).build();
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, HTTP_PORT), 500);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }
}
