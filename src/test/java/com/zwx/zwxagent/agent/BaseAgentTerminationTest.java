package com.zwx.zwxagent.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseAgentTerminationTest {

    @Test
    void returnsModelFinalResponseWhenNoToolIsNeeded() throws Exception {
        ScriptedAgent agent = new ScriptedAgent(false, List.of());
        agent.finishWith("本轮已完成资料整理。\n\n总结：已提取三个要点。");

        BaseAgent.RunResult result = run(agent);

        assertEquals("本轮已完成资料整理。\n\n总结：已提取三个要点。", result.answer());
    }

    @Test
    void stopsAfterFiveRoundsWithoutMeaningfulProgress() throws Exception {
        ScriptedAgent agent = new ScriptedAgent(true, List.of(
                new BaseAgent.ToolExecution("searchWeb", "{\\\"query\\\":\\\"test\\\"}", "SEARCH_UNAVAILABLE: no result")
        ));

        BaseAgent.RunResult result = run(agent);

        assertTrue(result.answer().contains("连续 5 轮没有获得新的有效结果"));
        assertEquals(5, agent.calls());
    }

    @Test
    void stopsWhenSameToolReturnsTheSameResultFiftyThreeTimes() throws Exception {
        BaseAgent.ToolExecution repeated = new BaseAgent.ToolExecution("searchWeb", "{\\\"query\\\":\\\"test\\\"}", "same result");
        ScriptedAgent agent = new ScriptedAgent(true, java.util.Collections.nCopies(53, repeated));

        BaseAgent.RunResult result = run(agent);

        assertTrue(result.answer().contains("同一工具返回相同结果已累计 53 次"));
        assertEquals(1, agent.calls());
    }

    @Test
    void stopsAfterTwentyRoundsEvenWhenEachRoundMakesProgress() throws Exception {
        ScriptedAgent agent = new ScriptedAgent(true, List.of()) {
            @Override
            protected List<ToolExecution> lastToolExecutions() {
                return List.of(new ToolExecution("searchWeb", "{}", "result-" + calls()));
            }
        };

        BaseAgent.RunResult result = run(agent);

        assertTrue(result.answer().contains("已达到最大执行轮数（20）"));
        assertEquals(20, agent.calls());
    }

    private BaseAgent.RunResult run(BaseAgent agent) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<BaseAgent.RunResult> result = new AtomicReference<>();
        agent.runStream("test", value -> {
            result.set(value);
            completed.countDown();
        });
        assertTrue(completed.await(3, TimeUnit.SECONDS));
        return result.get();
    }

    private static class ScriptedAgent extends BaseAgent {
        private final boolean emitsActivities;
        private final List<ToolExecution> toolExecutions;
        private String finalResponse;
        private int calls;

        private ScriptedAgent(boolean emitsActivities, List<ToolExecution> toolExecutions) {
            this.emitsActivities = emitsActivities;
            this.toolExecutions = toolExecutions;
            setMaxSteps(20);
        }

        void finishWith(String response) {
            this.finalResponse = response;
        }

        int calls() {
            return calls;
        }

        @Override
        public String step() {
            calls++;
            if (finalResponse != null) {
                setState(com.zwx.zwxagent.agent.model.AgentState.FINISHED);
                return finalResponse;
            }
            return "工具 searchWeb 返回的结果：test";
        }

        @Override
        protected boolean streamStepAsActivity() {
            return emitsActivities;
        }

        @Override
        protected List<ToolExecution> lastToolExecutions() {
            return toolExecutions;
        }
    }
}
