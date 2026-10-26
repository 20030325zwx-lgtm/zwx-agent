package com.zwx.zwxagent.agent.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class PlannerNodeKnowledgeRoutingTest {
    @Test
    void sendsKnowledgeQuestionsToOneGeneralWorkerWithoutCallingPlannerModel() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        PlannerNode planner = new PlannerNode(chatModel, null);
        OverAllState state = new OverAllState(Map.of(
                GraphState.INPUT, "比较宁夏北部与南部",
                GraphState.RUN_CONTEXT, RunContext.of(new org.springframework.ai.tool.ToolCallback[0],
                        "[28-ningxia.md#6] 银川平原依赖黄河灌溉。", () -> false)));

        Map<String, Object> result = planner.apply(state);

        var plan = (java.util.List<PlanStep>) result.get(GraphState.PLAN);
        assertEquals(1, plan.size());
        assertEquals(WorkerRole.GENERAL, plan.getFirst().role());
        assertTrue(plan.getFirst().detail().contains("不要联网"));
        verify(chatModel, times(1)).getDefaultOptions();
    }
}
