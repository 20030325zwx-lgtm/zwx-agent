package com.zwx.zwxagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentKnowledgeRagService {

    private final VectorStore vectorStore;

    public AgentKnowledgeRagService(@Qualifier("agentKnowledgeVectorStore") VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<Document> retrieve(String tenantId, String agentKey, String query, int topK, double similarityThreshold) {
        return vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression("tenantId == '" + tenantId + "' && agentKey == '" + agentKey + "'").build());
    }

    public String context(String tenantId, String agentKey, String query) {
        List<Document> documents = retrieve(tenantId, agentKey, query, 3, 0.55);
        if (documents.isEmpty()) return "";
        StringBuilder context = new StringBuilder("以下内容来自当前租户为该智能体上传的私有知识库：\n");
        for (Document document : documents) {
            String text = document.getText().replaceAll("\\s+", " ").trim();
            context.append("- ").append(text, 0, Math.min(1200, text.length())).append('\n');
        }
        return context.toString();
    }
}
