package com.zwx.zwxagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class LoveRagService {

    @Resource(name = "loveAppVectorStore")
    private VectorStore vectorStore;

    @Resource(name = "ragExecutor")
    private java.util.concurrent.Executor ragExecutor;

    @Resource
    private DashScopeRerankService rerankService;

    @Value("${app.love.rag.top-k:3}")
    private int topK;

    @Value("${app.love.rag.similarity-threshold:0.55}")
    private double similarityThreshold;

    @Value("${app.love.rag.recall-top-k:15}")
    private int recallTopK;

    @Value("${app.love.rag.recall-similarity-threshold:0.4}")
    private double recallSimilarityThreshold;

    @Value("${app.love.rag.timeout-ms:2500}")
    private long timeoutMs;

    public LoveRagTrace trace(String message, String model) {
        return retrieve(message, model).trace();
    }

    public LoveRagResult retrieve(String message, String model) {
        RetrievalOutcome outcome;
        CompletableFuture<RetrievalOutcome> search = CompletableFuture.supplyAsync(() -> recallAndRerank(message), ragExecutor);
        try {
            outcome = search.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            search.cancel(true);
            return timedOut(message, model);
        } catch (InterruptedException exception) {
            search.cancel(true);
            Thread.currentThread().interrupt();
            return unavailable(message, model, "知识库检索被中断，模型仅使用系统提示词与会话上下文回答。");
        } catch (Exception exception) {
            return unavailable(message, model, "知识库检索暂不可用，模型仅使用系统提示词与会话上下文回答。");
        }
        List<Document> documents = outcome.documents();
        String rerankModel = documents.isEmpty() ? null : outcome.rerankModel();
        List<Double> rerankScores = documents.isEmpty() ? null : outcome.rerankScores();
        List<LoveRetrievalCandidate> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            LoveKnowledgeReference reference = toReference(document);
            Double rerankScore = rerankScores == null ? null : rerankScores.get(i);
            candidates.add(new LoveRetrievalCandidate(reference.filename(), reference.section(), reference.objectKey(),
                    score(document), rerankScore));
        }
        List<LoveKnowledgeReference> references = documents.stream()
                .map(this::toReference)
                .collect(java.util.stream.Collectors.toMap(
                        LoveKnowledgeReference::objectKey,
                        reference -> reference,
                        (first, ignored) -> first,
                        LinkedHashMap::new))
                .values().stream().toList();
        String decision = documents.isEmpty()
                ? "没有文档达到相似度阈值，模型仅使用系统提示词与会话上下文回答。"
                : rerankModel != null
                        ? "向量召回候选后经 " + rerankModel + " 重排，取前 " + topK + " 条注入 RAG 上下文。"
                        : "重排不可用，按向量相似度取前 " + topK + " 条注入 RAG 上下文。";
        LoveRagTrace trace = new LoveRagTrace(message, topK, similarityThreshold, candidates, decision, references, model, true, false,
                rerankModel);
        return new LoveRagResult(trace, toContext(documents));
    }

    /** 单次检索的结果：最终文档 + 对应重排分（未重排为 null）+ 重排模型名。 */
    private record RetrievalOutcome(List<Document> documents, List<Double> rerankScores, String rerankModel) {
    }

    /**
     * 两段式检索：先放宽阈值扩大向量召回池，再由重排序器精排；重排不可用时降级为按向量分截断。
     */
    private RetrievalOutcome recallAndRerank(String message) {
        List<Document> pool = vectorStore.similaritySearch(SearchRequest.builder()
                .query(message).topK(Math.max(recallTopK, topK))
                .similarityThreshold(Math.min(recallSimilarityThreshold, similarityThreshold)).build());
        if (pool.isEmpty()) return new RetrievalOutcome(pool, null, null);
        List<Document> byVectorScore = pool.stream()
                .sorted(java.util.Comparator.comparingDouble((Document document) -> score(document)).reversed())
                .toList();
        List<String> texts = byVectorScore.stream().map(Document::getText).toList();
        return rerankService.rerank(message, texts)
                .<RetrievalOutcome>map(hits -> {
                    List<Document> documents = new java.util.ArrayList<>();
                    List<Double> scores = new java.util.ArrayList<>();
                    hits.stream().limit(topK).forEach(hit -> {
                        documents.add(byVectorScore.get(hit.index()));
                        scores.add(hit.score());
                    });
                    return new RetrievalOutcome(documents, scores, rerankService.modelName());
                })
                .orElseGet(() -> new RetrievalOutcome(byVectorScore.stream()
                        .filter(document -> score(document) >= similarityThreshold)
                        .limit(topK)
                        .toList(), null, null));
    }

    private LoveRagResult timedOut(String message, String model) {
        return unavailable(message, model, "知识库检索超过 " + timeoutMs + "ms 预算，已降级为仅使用系统提示词与会话上下文回答。", true);
    }

    private LoveRagResult unavailable(String message, String model, String decision) {
        return unavailable(message, model, decision, true);
    }

    private LoveRagResult unavailable(String message, String model, String decision, boolean degraded) {
        LoveRagTrace trace = new LoveRagTrace(message, topK, similarityThreshold, List.of(), decision,
                List.of(), model, true, degraded);
        return new LoveRagResult(trace, "");
    }

    private String toContext(List<Document> documents) {
        if (documents.isEmpty()) return "";
        StringBuilder context = new StringBuilder("以下资料来自内部情感知识库，仅在与用户问题相关时参考：\n");
        for (Document document : documents) {
            String text = document.getText().replaceAll("\\s+", " ").trim();
            context.append("- ").append(text, 0, Math.min(text.length(), 1200)).append('\n');
        }
        return context.toString();
    }

    private LoveKnowledgeReference toReference(Document document) {
        Object section = document.getMetadata().get("section");
        return new LoveKnowledgeReference(
                String.valueOf(document.getMetadata().get("filename")),
                section instanceof Number number ? number.intValue() : null,
                chunkIndex(document),
                String.valueOf(document.getMetadata().get("objectKey")),
                excerpt(document.getText()));
    }

    private Double score(Document document) {
        return document.getScore() == null ? 0.0 : document.getScore();
    }

    private Integer chunkIndex(Document document) {
        Object value = document.getMetadata().get("chunkIndex");
        return value instanceof Number number ? number.intValue() : null;
    }

    private String excerpt(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 280 ? normalized : normalized.substring(0, 280) + "...";
    }
}
