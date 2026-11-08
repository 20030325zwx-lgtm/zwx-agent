package com.zwx.zwxagent.agent.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.zwx.zwxagent.agent.execution.store.RunEventLog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** 单循环档（DirectNode）：思考文本直通 answerChunkSink、最终回答、停止早退、路由与检查点归属。 */
class DirectNodeTest {

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text, Map.of()))));
    }

    private static ChatModel textOnlyModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) { return chunk("block"); }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(chunk("事件溯源就是把"), chunk("系统状态记成事件流。"));
            }
        };
    }

    private OverAllState directState(ToolCallback[] tools, java.util.function.BooleanSupplier stopCheck,
                                     StringBuilder answerSink, List<ActivityEvent> activities) {
        OverAllState state = new OverAllState();
        state.registerKeyAndStrategy(GraphState.INPUT, new ReplaceStrategy());
        state.registerKeyAndStrategy(GraphState.RUN_ID, new ReplaceStrategy());
        state.registerKeyAndStrategy(GraphState.RUN_CONTEXT, new ReplaceStrategy());
        state.registerKeyAndStrategy(GraphState.ORCHESTRATION, new ReplaceStrategy());
        state.updateState(Map.of(
                GraphState.INPUT, "用一句话解释事件溯源",
                GraphState.RUN_ID, "run-direct-test",
                GraphState.RUN_CONTEXT, RunContext.of(tools, "", stopCheck, null, null, "conv-1",
                        answerSink::append, activities == null ? null : activities::add, true),
                GraphState.ORCHESTRATION, "single"));
        return state;
    }

    @Test
    void streamsAnswerAndDeliversFinalAnswer() throws Exception {
        DirectNode node = new DirectNode(textOnlyModel(), null, null, null);
        StringBuilder seen = new StringBuilder();
        OverAllState state = directState(new ToolCallback[0], () -> false, seen, null);

        Map<String, Object> output = node.apply(state);

        Assertions.assertEquals("事件溯源就是把系统状态记成事件流。", output.get(GraphState.FINAL_ANSWER));
        Assertions.assertEquals("事件溯源就是把系统状态记成事件流。", seen.toString());
        Assertions.assertFalse(((List<?>) output.get(GraphState.SSE_EVENTS)).isEmpty());
    }

    @Test
    void stopSignalSkipsExecution() throws Exception {
        DirectNode node = new DirectNode(Mockito.mock(ChatModel.class), null, null, null);
        OverAllState state = directState(new ToolCallback[0], () -> true, new StringBuilder(), null);

        Map<String, Object> output = node.apply(state);

        Assertions.assertTrue(((List<?>) output.get(GraphState.ACTIVITIES)).getFirst().toString().contains("停止"));
        Assertions.assertFalse(output.containsKey(GraphState.FINAL_ANSWER));
    }

    @Test
    void systemPromptTargetsUserDirectly() {
        Assertions.assertTrue(DirectNode.SYSTEM_PROMPT.contains("直接对用户负责"));
        Assertions.assertTrue(DirectNode.SYSTEM_PROMPT.contains("禁止"));
    }

    @Test
    void directRunsCheckpointAsDirectNodeAndResumeRerunsIt() {
        // completedNode：单循环标记下无论推进到哪，检查点都归属 direct 节点
        OverAllState state = new OverAllState();
        state.registerKeyAndStrategy(GraphState.ORCHESTRATION, new ReplaceStrategy());
        state.registerKeyAndStrategy(GraphState.FINAL_ANSWER, new ReplaceStrategy());
        state.updateState(Map.of(GraphState.ORCHESTRATION, "single", GraphState.FINAL_ANSWER, "答案"));
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_DIRECT, ManusGraphOrchestrator.completedNode(state));

        // nextNode / resumeNode：direct 自恢复（重跑本节点，工具副作用由幂等账本回放）
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_DIRECT,
                ManusGraphOrchestrator.nextNode(ManusGraphOrchestrator.NODE_DIRECT, "pass"));
        GraphCheckpoint checkpoint = new GraphCheckpoint(ManusGraphOrchestrator.NODE_DIRECT, ManusGraphOrchestrator.NODE_DIRECT,
                List.of(), List.of(), List.of(), List.of(), 0, "pass", "", "", "");
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_DIRECT, ManusGraphOrchestrator.resumeNode(checkpoint));

        // 图编排档不受影响：finalAnswer 归 aggregator
        OverAllState graphState = new OverAllState();
        graphState.registerKeyAndStrategy(GraphState.ORCHESTRATION, new ReplaceStrategy());
        graphState.registerKeyAndStrategy(GraphState.FINAL_ANSWER, new ReplaceStrategy());
        graphState.updateState(Map.of(GraphState.ORCHESTRATION, "graph", GraphState.FINAL_ANSWER, "答案"));
        Assertions.assertEquals(ManusGraphOrchestrator.NODE_AGGREGATOR, ManusGraphOrchestrator.completedNode(graphState));
    }

    @Test
    void eventLogHookIsRegisteredType() {
        // 防回归：事件日志 hook 的 run/end 事件类型常量存在（DirectNode 复用统一 hook 管线）
        Assertions.assertEquals("run/end", RunEventLog.RUN_END);
    }
}
