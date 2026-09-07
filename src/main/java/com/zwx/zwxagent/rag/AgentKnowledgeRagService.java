package com.zwx.zwxagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class AgentKnowledgeRagService {

    private final VectorStore vectorStore;

    private final DashScopeRerankService rerankService;

    @Resource(name = "ragExecutor")
    private java.util.concurrent.Executor ragExecutor;

    @Value("${app.agent.rag.top-k:3}")
    private int topK;

    @Value("${app.agent.rag.similarity-threshold:0.55}")
    private double similarityThreshold;

    @Value("${app.agent.rag.recall-top-k:15}")
    private int recallTopK;

    @Value("${app.agent.rag.recall-similarity-threshold:0.4}")
    private double recallSimilarityThreshold;

    @Value("${app.agent.rag.timeout-ms:3000}")
    private long timeoutMs;

    public AgentKnowledgeRagService(@Qualifier("agentKnowledgeVectorStore") VectorStore vectorStore,
                                    DashScopeRerankService rerankService) {
        this.vectorStore = vectorStore;
        this.rerankService = rerankService;
    }

    /** 纯向量检索（不重排），保留给需要原始结果的调用方。 */
    public List<Document> retrieve(String tenantId, String agentKey, String query, int topK, double similarityThreshold) {
        return vectorStore.similaritySearch(searchRequest(tenantId, agentKey, query, topK, similarityThreshold));
    }

    public String context(String tenantId, String agentKey, String query) {
        return retrieveWithContext(tenantId, agentKey, query).context();
    }

    public AgentKnowledgeRagResult retrieveWithContext(String tenantId, String agentKey, String query) {
        List<Document> documents;
        CompletableFuture<RetrievalOutcome> search = CompletableFuture
                .supplyAsync(() -> recall(tenantId, agentKey, query), ragExecutor);
        try {
            documents = search.get(timeoutMs, TimeUnit.MILLISECONDS).documents();
        } catch (TimeoutException exception) {
            search.cancel(true);
            return AgentKnowledgeRagResult.EMPTY;
        } catch (InterruptedException exception) {
            search.cancel(true);
            Thread.currentThread().interrupt();
            return AgentKnowledgeRagResult.EMPTY;
        } catch (Exception exception) {
            return AgentKnowledgeRagResult.EMPTY;
        }
        if (documents.isEmpty()) return new AgentKnowledgeRagResult("", List.of());
        StringBuilder context = new StringBuilder("以下内容来自当前租户为该智能体上传的私有知识库（仅当前生效版本）：\n");
        for (Document document : documents) {
            String text = document.getText().replaceAll("\\s+", " ").trim();
            Object version = document.getMetadata().get("versionNo");
            String versionTag = version instanceof Number number ? "（v" + number.intValue() + "）" : "";
            context.append("- ").append(document.getMetadata().get("filename")).append(versionTag)
                    .append("：").append(text, 0, Math.min(1200, text.length())).append('\n');
        }
        List<LoveKnowledgeReference> references = documents.stream().map(this::toReference)
                .collect(java.util.stream.Collectors.toMap(reference -> reference.objectKey() + "|" + reference.chunkIndex(), reference -> reference,
                        (first, ignored) -> first, LinkedHashMap::new)).values().stream().toList();
        return new AgentKnowledgeRagResult(context.toString(), references);
    }

    /** 单次检索的结果：最终文档 + 对应重排分（未重排为 null）。 */
    private record RetrievalOutcome(List<Document> documents, List<Double> rerankScores) {
    }

    /**
     * 两段式检索：版本有效性过滤（必须先于 rerank）→ 向量召回 → 逻辑文档去重 → 重排序精排；
     * 重排不可用时降级为按向量分截断。
     */
    private RetrievalOutcome recall(String tenantId, String agentKey, String query) {
        List<Document> pool = vectorStore.similaritySearch(searchRequest(tenantId, agentKey, query,
                Math.max(recallTopK, topK), Math.min(recallSimilarityThreshold, similarityThreshold)));
        // 防御过滤：发布切换期间可能存在脏向量，metadata 非 ACTIVE 的切片直接排除
        pool = pool.stream()
                .filter(document -> "ACTIVE".equals(String.valueOf(document.getMetadata().get("lifecycleStatus"))))
                .toList();
        if (pool.isEmpty()) return new RetrievalOutcome(pool, null);
        List<Document> byVectorScore = pool.stream()
                .sorted(java.util.Comparator.comparingDouble(this::score).reversed())
                .toList();
        // 同一逻辑文档只保留当前版本（正常数据下唯一 ACTIVE 已保证，此处兜底去重）
        java.util.Set<String> seenLogicalKeys = new java.util.HashSet<>();
        List<Document> deduped = byVectorScore.stream()
                .filter(document -> {
                    String logicalKey = String.valueOf(document.getMetadata().getOrDefault("logicalKey",
                            document.getMetadata().get("documentId")));
                    return seenLogicalKeys.add(logicalKey);
                })
                .toList();
        List<String> texts = deduped.stream().map(Document::getText).toList();
        return rerankService.rerank(query, texts)
                .<RetrievalOutcome>map(hits -> {
                    List<Document> documents = new ArrayList<>();
                    List<Double> scores = new ArrayList<>();
                    hits.stream().limit(topK).forEach(hit -> {
                        documents.add(deduped.get(hit.index()));
                        scores.add(hit.score());
                    });
                    return new RetrievalOutcome(documents, scores);
                })
                .orElseGet(() -> new RetrievalOutcome(deduped.stream()
                        .filter(document -> score(document) >= similarityThreshold)
                        .limit(topK)
                        .toList(), null));
    }

    private final java.util.Set<String> seenLogicalKeys = new java.util.HashSet<>();

    private SearchRequest searchRequest(String tenantId, String agentKey, String query, int topK, double similarityThreshold) {
        return SearchRequest.builder().query(query).topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression("tenantId == '" + tenantId + "' && agentKey == '" + agentKey
                        + "' && lifecycleStatus == 'ACTIVE'").build();
    }

    private double score(Document document) {
        return document.getScore() == null ? 0.0 : document.getScore();
    }

    private LoveKnowledgeReference toReference(Document document) {
        Object chunk = document.getMetadata().get("chunkIndex");
        Integer chunkIndex = chunk instanceof Number number ? number.intValue() : null;
        Object version = document.getMetadata().get("versionNo");
        Integer versionNo = version instanceof Number number ? number.intValue() : null;
        Object logicalKey = document.getMetadata().get("logicalKey");
        String text = document.getText().replaceAll("\\s+", " ").trim();
        return new LoveKnowledgeReference(String.valueOf(document.getMetadata().get("filename")), null, chunkIndex,
                String.valueOf(document.getMetadata().get("objectKey")), text.length() <= 280 ? text : text.substring(0, 280) + "...",
                logicalKey == null ? null : String.valueOf(logicalKey), versionNo);
    }
}
