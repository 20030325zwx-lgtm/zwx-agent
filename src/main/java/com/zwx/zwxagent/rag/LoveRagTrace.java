package com.zwx.zwxagent.rag;

import java.util.List;

public record LoveRagTrace(String query, int topK, double similarityThreshold,
                           List<LoveRetrievalCandidate> candidates, String decision,
                           List<LoveKnowledgeReference> references, String model, boolean streaming) {
}
