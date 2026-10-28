package com.zwx.zwxagent.agent.graph;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class ResumeRoutingTest {

    @Test
    void missingCheckpointRestartsFromPlanner() {
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_PLANNER,
                ManusGraphOrchestrator.resumeNode(null));
    }

    @Test
    void plannerCheckpointResumesWorkers() {
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_WORKERS,
                ManusGraphOrchestrator.resumeNode(checkpoint(ManusGraphOrchestrator.NODE_PLANNER, null, List.of())));
    }

    @Test
    void workersCheckpointResumesVerifier() {
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_VERIFIER,
                ManusGraphOrchestrator.resumeNode(checkpoint(ManusGraphOrchestrator.NODE_WORKERS, null, List.of("done"))));
    }

    @Test
    void verifierPassResumesAggregator() {
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_AGGREGATOR,
                ManusGraphOrchestrator.resumeNode(checkpoint(ManusGraphOrchestrator.NODE_VERIFIER, "pass", List.of("done"))));
    }

    @Test
    void verifierReviseResumesWorkersAndClearsPreviousResults() {
        GraphCheckpoint checkpoint = checkpoint(ManusGraphOrchestrator.NODE_VERIFIER, "revise", List.of("old"));
        String next = ManusGraphOrchestrator.resumeNode(checkpoint);
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_WORKERS, next);
        GraphCheckpoint prepared = ManusGraphOrchestrator.prepareResumeCheckpoint(checkpoint, next);
        Assertions.assertTrue(prepared.taskResults().isEmpty());
    }

    @Test
    void completedAggregatorCheckpointRegeneratesAnswer() {
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_AGGREGATOR,
                ManusGraphOrchestrator.resumeNode(checkpoint(ManusGraphOrchestrator.NODE_AGGREGATOR, "pass", List.of("done"))));
    }

    @Test
    void resumeRouterDefaultsToPlanner() {
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_PLANNER,
                ManusGraphOrchestrator.routeResumeFrom(null));
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_WORKERS,
                ManusGraphOrchestrator.routeResumeFrom(ManusGraphOrchestrator.NODE_WORKERS));
    }

    @Test
    void resumeStateKeepsOriginalInputAndCheckpointData() throws Exception {
        ManusGraphOrchestrator orchestrator = new ManusGraphOrchestrator(
                org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class), Runnable::run);
        orchestrator.init();
        GraphCheckpoint checkpoint = new GraphCheckpoint(
                ManusGraphOrchestrator.NODE_PLANNER, ManusGraphOrchestrator.NODE_WORKERS,
                List.of(new PlanStep("原始任务", WorkerRole.GENERAL, "按原始请求执行")),
                List.of(), List.of(), List.of(), 0, "unverified", "", "", "");
        ManusGraphOrchestrator.ManusRunRequest request = new ManusGraphOrchestrator.ManusRunRequest(
                "conversation", "tenant", "user", null, "原始请求", "历史", new org.springframework.ai.tool.ToolCallback[0],
                "", null, null, "run-1", "EDIT", checkpoint, ManusGraphOrchestrator.NODE_WORKERS, () -> { }, result -> { });
        var state = orchestrator.resumeInputs(request, RunContext.of(new org.springframework.ai.tool.ToolCallback[0], "", () -> false));
        Assertions.assertEquals("原始请求", state.get(GraphState.INPUT));
        Assertions.assertEquals("历史", state.get(GraphState.HISTORY));
        Assertions.assertEquals(1, ((List<?>) state.get(GraphState.PLAN)).size());
    }

    @Test
    void completedNodeIsInferredFromAccumulatedState() {
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_PLANNER, ManusGraphOrchestrator.completedNode(
                state(java.util.Map.of(GraphState.PLAN, List.of(new PlanStep("t", WorkerRole.GENERAL, "d"))))));
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_WORKERS, ManusGraphOrchestrator.completedNode(
                state(java.util.Map.of(GraphState.TASK_RESULTS, List.of("done")))));
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_VERIFIER, ManusGraphOrchestrator.completedNode(
                state(java.util.Map.of(GraphState.VERIFICATION_STATUS, "pass"))));
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_AGGREGATOR, ManusGraphOrchestrator.completedNode(
                state(java.util.Map.of(GraphState.FINAL_ANSWER, "answer"))));
        Assertions.assertNull(ManusGraphOrchestrator.completedNode(state(java.util.Map.of())));
    }

    private static com.alibaba.cloud.ai.graph.OverAllState state(java.util.Map<String, Object> values) {
        var state = new com.alibaba.cloud.ai.graph.OverAllState();
        state.registerKeyAndStrategy(java.util.Map.of(
                GraphState.PLAN, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy(),
                GraphState.TASK_RESULTS, new com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy(),
                GraphState.VERIFICATION_STATUS, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy(),
                GraphState.FINAL_ANSWER, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy()));
        state.input(values);
        return state;
    }

    private static GraphCheckpoint checkpoint(String lastNode, String verification, List<String> results) {
        return new GraphCheckpoint(lastNode, null, List.of(), results, List.of(), List.of(),
                0, verification, "", "", "");
    }
}
