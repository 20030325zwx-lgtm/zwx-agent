package com.zwx.zwxagent.knowledge.retrieval;

import java.util.List;

public record RagRetrievalTrace(String traceId, String query, KnowledgeRetrievalStatus status,
                                int vectorCandidateCount, int lexicalCandidateCount, int fusedCandidateCount,
                                int selectedCount, boolean reranked, boolean degraded,
                                long vectorMillis, long lexicalMillis, long rerankMillis, long totalMillis,
                                List<RagTraceCandidate> candidates) {
}
