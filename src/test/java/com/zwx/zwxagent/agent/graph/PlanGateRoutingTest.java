package com.zwx.zwxagent.agent.graph;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class PlanGateRoutingTest {

    @Test
    void singleStepPlanSkipsGate() {
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_WORKERS, ManusGraphOrchestrator.planGateTarget(1));
    }

    @Test
    void multiStepPlanGoesThroughGate() {
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_PLAN_GATE, ManusGraphOrchestrator.planGateTarget(2));
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_PLAN_GATE, ManusGraphOrchestrator.planGateTarget(4));
    }

    @Test
    void resumeFromPlannerCheckpointStartsWorkersWithoutReGate() {
        GraphCheckpoint checkpoint = new GraphCheckpoint(
                ManusGraphOrchestrator.NODE_PLANNER, ManusGraphOrchestrator.NODE_WORKERS,
                List.of(new PlanStep("s1", "甲", WorkerRole.GENERAL, "d", List.of()),
                        new PlanStep("s2", "乙", WorkerRole.GENERAL, "d", List.of("s1"))),
                List.of(), List.of(), List.of(), 0, "plan_pending", "", "", "", List.of());
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_WORKERS, ManusGraphOrchestrator.resumeNode(checkpoint));
    }

    @Test
    void mermaidDiagramContainsPlanGate() throws Exception {
        ManusGraphOrchestrator orchestrator = new ManusGraphOrchestrator(
                org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class), Runnable::run);
        orchestrator.init();
        Assertions.assertTrue(orchestrator.mermaidDiagram().contains(ManusGraphOrchestrator.NODE_PLAN_GATE));
    }
}
