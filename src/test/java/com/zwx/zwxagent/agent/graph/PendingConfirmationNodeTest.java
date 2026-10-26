package com.zwx.zwxagent.agent.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

/** 挂起等待工具确认时，质检与交付节点必须早退：不再消耗模型调用，也不对半成品给验收结论。 */
class PendingConfirmationNodeTest {

    private OverAllState stateWithPending(List<String> tools) {
        OverAllState state = new OverAllState();
        state.registerKeyAndStrategy(GraphState.PENDING_CONFIRMATION, new ReplaceStrategy());
        state.registerKeyAndStrategy(GraphState.REVISION, new ReplaceStrategy());
        state.registerKeyAndStrategy(GraphState.VERIFICATION_STATUS, new ReplaceStrategy());
        state.registerKeyAndStrategy(GraphState.VERIFICATION_FEEDBACK, new ReplaceStrategy());
        state.registerKeyAndStrategy(GraphState.FINAL_ANSWER, new ReplaceStrategy());
        state.registerKeyAndStrategy(GraphState.RUN_CONTEXT, new ReplaceStrategy());
        state.updateState(Map.of(GraphState.PENDING_CONFIRMATION, tools));
        return state;
    }

    @Test
    void verifierSkipsReviewWhenConfirmationPending() {
        ChatModel chatModel = Mockito.mock(ChatModel.class);
        OverAllState state = stateWithPending(List.of("executeTerminalCommand"));
        Map<String, Object> result = new VerifierNode(chatModel, null).apply(state);
        // 早退的直接证据：状态为 paused 而非模型给出的 pass/revise/unverified
        Assertions.assertEquals("paused", result.get(GraphState.VERIFICATION_STATUS));
        Assertions.assertTrue(((List<?>) result.get(GraphState.ACTIVITIES)).getFirst().toString().contains("确认"));
    }

    @Test
    void aggregatorDeliversPlaceholderWithoutModelCall() {
        ChatModel chatModel = Mockito.mock(ChatModel.class);
        OverAllState state = stateWithPending(List.of("executeTerminalCommand", "downloadResource"));
        Map<String, Object> result = new AggregatorNode(chatModel, null).apply(state);
        // 早退的直接证据：答案为固定占位说明且列出待确认工具（真实模型路径无法保证逐字包含工具名）
        String answer = (String) result.get(GraphState.FINAL_ANSWER);
        Assertions.assertTrue(answer.contains("executeTerminalCommand"));
        Assertions.assertTrue(answer.contains("downloadResource"));
        Assertions.assertTrue(answer.contains("确认"));
    }
}
