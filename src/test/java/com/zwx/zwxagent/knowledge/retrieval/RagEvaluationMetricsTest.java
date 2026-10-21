package com.zwx.zwxagent.knowledge.retrieval;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvaluationMetricsTest {
    @Test
    void measuresRecallAndReciprocalRankAgainstGroundTruthEvidence() {
        List<String> retrieved = List.of("other", "synthetic-acme-2026-bonus-policy");
        Set<String> expected = Set.of("synthetic-acme-2026-bonus-policy");
        assertEquals(1, RagEvaluationMetrics.recallAtK(retrieved, expected, 2));
        assertEquals(0.5, RagEvaluationMetrics.reciprocalRank(retrieved, expected));
    }

    @Test
    void keepsTheOfflineCorpusExplicitlySyntheticAndNonAnswerableCasesPresent() throws Exception {
        Path corpus = Path.of(getClass().getResource("/rag-evaluation/synthetic-acme-2026-bonus-policy.md").toURI());
        Path cases = Path.of(getClass().getResource("/rag-evaluation/cases.jsonl").toURI());
        assertTrue(Files.readString(corpus).contains("非真实企业政策"));
        assertTrue(Files.readString(cases).contains("\"answerable\":false"));
    }
}
