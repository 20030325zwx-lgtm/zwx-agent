package com.zwx.zwxagent.agent.hook;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class AgentHookPipelineTest {

    @AfterEach
    void reset() {
        AgentHooks.install(null);
    }

    @Test
    void hooksExecuteInOrderAndFailuresAreIsolated() {
        List<String> calls = new ArrayList<>();
        AgentHook first = new TestHook("first", 10) {
            @Override
            public void beforeStep(AgentRunContext context) {
                calls.add("first");
                throw new RuntimeException("观察型 hook 崩溃不应影响后续");
            }
        };
        AgentHook second = new TestHook("second", 20) {
            @Override
            public void beforeStep(AgentRunContext context) {
                calls.add("second");
            }
        };
        AgentHookPipeline pipeline = new AgentHookPipeline(List.of(second, first));
        pipeline.fireBeforeStep(context());
        Assertions.assertEquals(List.of("first", "second"), calls);
    }

    @Test
    void duplicateIdIsDeduplicatedKeepingLowerOrder() {
        AgentHook a = new TestHook("same-id", 5);
        AgentHook b = new TestHook("same-id", 50);
        AgentHookPipeline pipeline = new AgentHookPipeline(List.of(a, b));
        Assertions.assertDoesNotThrow(() -> pipeline.fireBeforeStep(context()));
        Assertions.assertEquals(1, pipeline.hooks.size());
        Assertions.assertSame(a, pipeline.hooks.get("same-id"));
    }

    @Test
    void abortExceptionPropagatesFromBeforeToolCalls() {
        AgentHook guard = new TestHook("guard", 1) {
            @Override
            public void beforeToolCalls(AgentRunContext context, List<PlannedToolCall> plannedCalls) {
                throw new HookAbortException("禁止访问外部网络");
            }
        };
        AgentHook later = new TestHook("later", 99) {
            @Override
            public void beforeToolCalls(AgentRunContext context, List<PlannedToolCall> plannedCalls) {
                plannedCalls.clear();
            }
        };
        AgentHookPipeline pipeline = new AgentHookPipeline(List.of(guard, later));
        List<PlannedToolCall> planned = new ArrayList<>(List.of(new PlannedToolCall("1", "function", "web", "{}")));
        Assertions.assertThrows(HookAbortException.class, () -> pipeline.fireBeforeToolCalls(context(), planned));
        Assertions.assertEquals(1, planned.size());
    }

    @Test
    void beforeToolCallsCanModifyPlan() {
        AgentHook transformer = new TestHook("transformer", 1) {
            @Override
            public void beforeToolCalls(AgentRunContext context, List<PlannedToolCall> plannedCalls) {
                plannedCalls.removeIf(call -> call.name().equals("terminal"));
                plannedCalls.forEach(call -> call.arguments("{\"safe\":true}"));
            }
        };
        AgentHookPipeline pipeline = new AgentHookPipeline(List.of(transformer));
        List<PlannedToolCall> planned = new ArrayList<>(List.of(
                new PlannedToolCall("1", "function", "web", "{}"),
                new PlannedToolCall("2", "function", "terminal", "{\"cmd\":\"rm -rf\"}")));
        pipeline.fireBeforeToolCalls(context(), planned);
        Assertions.assertEquals(1, planned.size());
        Assertions.assertEquals("web", planned.get(0).name());
        Assertions.assertEquals("{\"safe\":true}", planned.get(0).arguments());
    }

    @Test
    void terminalCallbacksAllRunEvenIfSomeFail() {
        List<String> calls = new ArrayList<>();
        AgentHook flaky = new TestHook("flaky", 1) {
            @Override
            public void onFinish(AgentRunContext context) {
                calls.add("onFinish");
                throw new RuntimeException("boom");
            }

            @Override
            public void afterRun(AgentRunContext context) {
                calls.add("afterRun");
            }
        };
        AgentHookPipeline pipeline = new AgentHookPipeline(List.of(flaky));
        AgentRunContext context = context();
        Assertions.assertDoesNotThrow(() -> pipeline.fireOnFinish(context));
        Assertions.assertDoesNotThrow(() -> pipeline.fireAfterRun(context));
        Assertions.assertEquals(List.of("onFinish", "afterRun"), calls);
    }

    @Test
    void staticHolderServesPipeline() {
        Assertions.assertNull(AgentHooks.pipeline());
        AgentHookPipeline pipeline = new AgentHookPipeline(List.of());
        pipeline.install();
        Assertions.assertSame(pipeline, AgentHooks.pipeline());
    }

    private AgentRunContext context() {
        return AgentRunContext.builder().agentName("test-agent").userPrompt("hello").build();
    }

    private static class TestHook implements AgentHook {
        private final String id;
        private final int order;

        TestHook(String id, int order) {
            this.id = id;
            this.order = order;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int order() {
            return order;
        }
    }
}
