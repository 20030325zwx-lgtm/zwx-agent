package com.zwx.zwxagent.agent.graph;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VerifierRoutingTest {

    @Test
    void reviseRoutesBackToWorkers() {
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_WORKERS, ManusGraphOrchestrator.route("revise"));
    }

    @Test
    void passRoutesToAggregator() {
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_AGGREGATOR, ManusGraphOrchestrator.route("pass"));
    }

    @Test
    void missingStatusDefaultsToAggregator() {
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_AGGREGATOR, ManusGraphOrchestrator.route(null));
    }

    @Test
    void extractsFencedJson() {
        Assertions.assertEquals("{\"status\":\"pass\"}", VerifierNode.extractJson("```json\n{\"status\":\"pass\"}\n```"));
        Assertions.assertEquals("{\"a\":1}", VerifierNode.extractJson("前置说明 {\"a\":1} 后置说明"));
    }

    @Test
    void revisionBudgetBlocksSecondRework() throws Exception {
        // VerifierNode: 上一轮 revision 已达上限时，强制放行
        com.alibaba.cloud.ai.graph.OverAllState state = new com.alibaba.cloud.ai.graph.OverAllState();
        state.registerKeyAndStrategy(GraphState.REVISION, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
        state.registerKeyAndStrategy(GraphState.VERIFICATION_STATUS, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
        state.registerKeyAndStrategy(GraphState.VERIFICATION_FEEDBACK, new com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy());
        state.registerKeyAndStrategy(GraphState.ACTIVITIES, new com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy());
        state.updateState(java.util.Map.of(
                GraphState.REVISION, VerifierNode.MAX_REVISIONS,
                GraphState.VERIFICATION_STATUS, "revise"));
        var result = new VerifierNode(org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class)).apply(state);
        Assertions.assertEquals("pass", result.get(GraphState.VERIFICATION_STATUS));
    }
}
