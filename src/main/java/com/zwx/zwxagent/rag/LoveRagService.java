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

    public List<LoveKnowledgeReference> findReferences(String message) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                        .query(message)
                        .topK(3)
                        .similarityThreshold(0.55)
                        .build())
                .stream()
                .map(this::toReference)
                .collect(java.util.stream.Collectors.toMap(
                        LoveKnowledgeReference::objectKey,
                        reference -> reference,
                        (first, ignored) -> first,
                        LinkedHashMap::new))
                .values().stream().toList();
    }

    private LoveKnowledgeReference toReference(Document document) {
        Object section = document.getMetadata().get("section");
        return new LoveKnowledgeReference(
                String.valueOf(document.getMetadata().get("filename")),
                section instanceof Number number ? number.intValue() : null,
                String.valueOf(document.getMetadata().get("objectKey")));
    }
}
