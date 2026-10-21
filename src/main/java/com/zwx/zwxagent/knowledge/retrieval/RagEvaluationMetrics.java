package com.zwx.zwxagent.knowledge.retrieval;

import java.util.List;
import java.util.Set;

/** Pure metrics used by an offline evaluation runner; no model call is hidden in this class. */
public final class RagEvaluationMetrics {
    private RagEvaluationMetrics() { }

    public static double recallAtK(List<String> retrievedDocumentIds, Set<String> expectedDocumentIds, int k) {
        if (expectedDocumentIds.isEmpty()) return retrievedDocumentIds.stream().limit(k).noneMatch(id -> id != null && !id.isBlank()) ? 1 : 0;
        return retrievedDocumentIds.stream().limit(k).anyMatch(expectedDocumentIds::contains) ? 1 : 0;
    }

    public static double reciprocalRank(List<String> retrievedDocumentIds, Set<String> expectedDocumentIds) {
        for (int index = 0; index < retrievedDocumentIds.size(); index++) {
            if (expectedDocumentIds.contains(retrievedDocumentIds.get(index))) return 1.0 / (index + 1);
        }
        return 0;
    }
}
