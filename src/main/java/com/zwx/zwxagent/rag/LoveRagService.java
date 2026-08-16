package com.zwx.zwxagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;

@Service
public class LoveRagService {

    @Resource(name = "loveAppVectorStore")
    private VectorStore vectorStore;

    public LoveRagTrace trace(String message, String model) {
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(message)
                        .topK(3)
                        .similarityThreshold(0.55)
                        .build());
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
        return new LoveRagTrace(message, 3, 0.55, candidates, decision, references, model, true);
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
