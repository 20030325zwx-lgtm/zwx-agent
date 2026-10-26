package com.zwx.zwxagent.agent.core;

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
        // 每轮换参数但结果相同：参数指纹各不相同，只能靠「无新有效结果」计数兜住
        ScriptedAgent agent = new ScriptedAgent(true, List.of()) {
            @Override
            protected List<ToolExecution> lastToolExecutions() {
                return List.of(new ToolExecution("searchWeb",
                        "{\\\"query\\\":\\\"q" + calls() + "\\\"}", "SEARCH_UNAVAILABLE: no result"));
            }
        };

        BaseAgent.RunResult result = run(agent);

        assertTrue(result.answer().contains("连续 5 轮没有获得新的有效结果"));
        assertEquals(5, agent.calls());
    }

    @Test
    void stopsWhenSameArgsRepeatWithoutNewResultInOneStep() throws Exception {
        // 一步内同参数重复调用且结果无效：参数指纹在第 3 次触发熔断（快于无进展的 5 轮）
        BaseAgent.ToolExecution repeated = new BaseAgent.ToolExecution(
                "searchWeb", "{\\\"query\\\":\\\"test\\\"}", "SEARCH_UNAVAILABLE: no result");
        ScriptedAgent agent = new ScriptedAgent(true, java.util.Collections.nCopies(3, repeated));

        BaseAgent.RunResult result = run(agent);

        assertTrue(result.answer().contains("相同参数重复调用 3 次"));
        assertEquals(1, agent.calls());
    }

    @Test
    void stopsWhenSameArgsRepeatAcrossStepsWithStaleResult() throws Exception {
        // 跨步同参数循环：首轮结果算新（放行），之后结果不再是新的，第 3 次调用熔断
        ScriptedAgent agent = new ScriptedAgent(true, List.of()) {
            @Override
            protected List<ToolExecution> lastToolExecutions() {
                return List.of(new ToolExecution("searchWeb", "{\\\"query\\\":\\\"test\\\"}", "same content"));
            }
        };

        BaseAgent.RunResult result = run(agent);

        assertTrue(result.answer().contains("相同参数重复调用 3 次"));
        assertEquals(3, agent.calls());
    }

    @Test
    void repeatsWithSameArgsAreAllowedWhileMakingProgress() throws Exception {
        // 同参数但每轮拿到新的有效结果：正当重试/翻页场景，必须放行到自然结束
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

    @Test
    void retryWithSameArgsThatFinallySucceedsIsNotALoop() throws Exception {
        // 第一轮失败、第二轮同参数重试成功：有进展的轮次豁免参数循环判定
        ScriptedAgent agent = new ScriptedAgent(true, List.of()) {
            @Override
            protected List<ToolExecution> lastToolExecutions() {
                return List.of(calls() == 1
                        ? new ToolExecution("searchWeb", "{\\\"query\\\":\\\"test\\\"}", "error: timeout")
                        : new ToolExecution("searchWeb", "{\\\"query\\\":\\\"test\\\"}", "found content " + calls()));
            }
        };

        BaseAgent.RunResult result = run(agent);

        assertTrue(result.answer().contains("已达到最大执行轮数（20）"));
        assertEquals(20, agent.calls());
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

    @Test
    void syncRunPathAlsoStopsOnToolLoop() {
        // 图编排的 worker 走同步 run()：防护必须同样生效
        BaseAgent.ToolExecution repeated = new BaseAgent.ToolExecution(
                "searchWeb", "{\\\"query\\\":\\\"test\\\"}", "SEARCH_UNAVAILABLE: no result");
        ScriptedAgent agent = new ScriptedAgent(true, java.util.Collections.nCopies(3, repeated));

        String answer = agent.run("test");

        assertTrue(answer.contains("任务已按策略终止："));
        assertTrue(answer.contains("相同参数重复调用 3 次"));
        assertEquals(1, agent.calls());
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
