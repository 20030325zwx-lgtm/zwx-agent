package com.zwx.zwxagent.rag;

/** Safe to persist in traces: it deliberately contains no query or document text. */
public record KnowledgeRetrievalOutcome(KnowledgeRetrievalStatus status, int recalledCount, int selectedCount,
                                        boolean reranked, boolean degraded) {
    public static KnowledgeRetrievalOutcome success(int recalledCount, int selectedCount, boolean reranked) {
        return new KnowledgeRetrievalOutcome(KnowledgeRetrievalStatus.SUCCESS, recalledCount, selectedCount, reranked, false);
    }
    public static KnowledgeRetrievalOutcome noHit() {
        return new KnowledgeRetrievalOutcome(KnowledgeRetrievalStatus.NO_HIT, 0, 0, false, false);
    }
    public static KnowledgeRetrievalOutcome unavailable(KnowledgeRetrievalStatus status) {
        return new KnowledgeRetrievalOutcome(status, 0, 0, false, true);
    }
}
