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

    @Value("${app.love.rag.top-k:3}")
    private int topK;

    @Value("${app.love.rag.similarity-threshold:0.55}")
    private double similarityThreshold;

    @Value("${app.love.rag.timeout-ms:800}")
    private long timeoutMs;

    public LoveRagTrace trace(String message, String model) {
        return retrieve(message, model).trace();
    }

    public LoveRagResult retrieve(String message, String model) {
        List<Document> documents;
        CompletableFuture<List<Document>> search = CompletableFuture.supplyAsync(() -> vectorStore.similaritySearch(SearchRequest.builder()
                .query(message).topK(topK).similarityThreshold(similarityThreshold).build()));
        try {
            documents = search.get(timeoutMs, TimeUnit.MILLISECONDS);
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
        List<LoveRetrievalCandidate> candidates = documents.stream().map(this::toCandidate).toList();
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
                : "命中文档达到相似度阈值，已注入 RAG 上下文并用于生成回答。";
        LoveRagTrace trace = new LoveRagTrace(message, topK, similarityThreshold, candidates, decision, references, model, true);
        return new LoveRagResult(trace, toContext(documents));
    }

    private LoveRagResult timedOut(String message, String model) {
        return unavailable(message, model, "知识库检索超过 " + timeoutMs + "ms 预算，已降级为仅使用系统提示词与会话上下文回答。");
    }

    private LoveRagResult unavailable(String message, String model, String decision) {
        return new LoveRagResult(new LoveRagTrace(message, topK, similarityThreshold, List.of(), decision,
                List.of(), model, true), "");
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
                String.valueOf(document.getMetadata().get("objectKey")));
    }

    private LoveRetrievalCandidate toCandidate(Document document) {
        LoveKnowledgeReference reference = toReference(document);
        return new LoveRetrievalCandidate(reference.filename(), reference.section(), reference.objectKey(), document.getScore());
    }
}
