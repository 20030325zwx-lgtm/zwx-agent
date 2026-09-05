package com.zwx.zwxagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class AgentKnowledgeRagService {

    private final VectorStore vectorStore;

    @Resource(name = "ragExecutor")
    private java.util.concurrent.Executor ragExecutor;

    @Value("${app.agent.rag.timeout-ms:3000}")
    private long timeoutMs;

    public AgentKnowledgeRagService(@Qualifier("agentKnowledgeVectorStore") VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<Document> retrieve(String tenantId, String agentKey, String query, int topK, double similarityThreshold) {
        return vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression("tenantId == '" + tenantId + "' && agentKey == '" + agentKey + "'").build());
    }

    public String context(String tenantId, String agentKey, String query) {
        return retrieveWithContext(tenantId, agentKey, query).context();
    }

    public AgentKnowledgeRagResult retrieveWithContext(String tenantId, String agentKey, String query) {
        List<Document> documents;
        CompletableFuture<List<Document>> search = CompletableFuture
                .supplyAsync(() -> retrieve(tenantId, agentKey, query, 3, 0.55), ragExecutor);
        try {
            documents = search.get(timeoutMs, TimeUnit.MILLISECONDS);
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
        StringBuilder context = new StringBuilder("以下内容来自当前租户为该智能体上传的私有知识库：\n");
        for (Document document : documents) {
            String text = document.getText().replaceAll("\\s+", " ").trim();
            context.append("- ").append(text, 0, Math.min(1200, text.length())).append('\n');
        }
        List<LoveKnowledgeReference> references = documents.stream().map(this::toReference)
                .collect(java.util.stream.Collectors.toMap(reference -> reference.objectKey() + "|" + reference.chunkIndex(), reference -> reference,
                        (first, ignored) -> first, LinkedHashMap::new)).values().stream().toList();
        return new AgentKnowledgeRagResult(context.toString(), references);
    }

    private LoveKnowledgeReference toReference(Document document) {
        Object chunk = document.getMetadata().get("chunkIndex");
        Integer chunkIndex = chunk instanceof Number number ? number.intValue() : null;
        String text = document.getText().replaceAll("\\s+", " ").trim();
        return new LoveKnowledgeReference(String.valueOf(document.getMetadata().get("filename")), null, chunkIndex,
                String.valueOf(document.getMetadata().get("objectKey")), text.length() <= 280 ? text : text.substring(0, 280) + "...");
    }
}
