package com.zwx.zwxagent.agent.graph;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ManusGraphOrchestratorInterruptionTest {

    @Test
    void completedAnswerPrefersFinalAggregatorOutput() {
        Assertions.assertEquals("final", ManusGraphOrchestrator.completedAnswer(
                "final", new StringBuilder("streamed"), false));
    }

    @Test
    void interruptedAggregatorKeepsStreamedDraft() {
        Assertions.assertEquals("partial answer", ManusGraphOrchestrator.completedAnswer(
                "", new StringBuilder("partial answer"), true));
    }

    @Test
    void interruptedBeforeAggregatorPersistsVisiblePlaceholder() {
        String answer = ManusGraphOrchestrator.completedAnswer("", new StringBuilder(), true);
        Assertions.assertTrue(answer.contains("中断"));
    }

    @Test
    void failedBeforeAggregatorDoesNotPretendItWasInterrupted() {
        String answer = ManusGraphOrchestrator.completedAnswer("", new StringBuilder(), false);
        Assertions.assertTrue(answer.contains("失败"));
    }
}
