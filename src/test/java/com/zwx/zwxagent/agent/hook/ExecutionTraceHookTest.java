package com.zwx.zwxagent.agent.hook;

import com.zwx.zwxagent.agent.BaseAgent;
import com.zwx.zwxagent.execution.AgentExecutionEvent;
import com.zwx.zwxagent.execution.AgentExecutionTraceService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionTraceHookTest {

    private final AgentExecutionTraceService traceService = mock(AgentExecutionTraceService.class);
    private final ExecutionTraceHook hook = new ExecutionTraceHook(traceService);

    @Test
    void recordsRunStepToolLifecycle() {
        when(traceService.record(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);
        AgentRunContext context = AgentRunContext.builder()
                .runId("run-1").tenantId("default").agentKey("super").conversationId("conv-1")
                .agentName("worker-1").userPrompt("帮我调研").build();

        hook.beforeRun(context);
        context.currentStep(1);
        hook.afterStep(context, "已调用搜索工具完成资料收集");
        hook.afterToolCalls(context, List.of(new BaseAgent.ToolExecution("web", "{}", "搜索结果内容")));
        hook.onFinish(context);

        ArgumentCaptor<String> phase = ArgumentCaptor.forClass(String.class);
        verify(traceService, org.mockito.Mockito.times(4)).record(
                eq("run-1"), eq("default"), eq("super"), eq("conv-1"), phase.capture(), anyString(), any());
        Assertions.assertEquals(List.of("agent_started", "step", "tool", "agent_finished"), phase.getAllValues());
    }

    @Test
    void skipsWhenIdentityMissing() {
        AgentRunContext context = AgentRunContext.builder().agentName("worker").userPrompt("hello").build();
        hook.beforeRun(context);
        hook.afterStep(context, "ok");
        hook.afterToolCalls(context, List.of());
        hook.onFinish(context);
        verify(traceService, org.mockito.Mockito.never())
                .record(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void recordFailureIsSwallowed() {
        when(traceService.record(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("db down"));
        AgentRunContext context = AgentRunContext.builder()
                .runId("run-1").tenantId("default").agentKey("super").conversationId("conv-1")
                .agentName("worker").userPrompt("hello").build();
        Assertions.assertDoesNotThrow(() -> hook.beforeRun(context));
    }

    @Test
    void summariesAreCompacted() {
        when(traceService.record(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);
        AgentExecutionEvent ignored = new AgentExecutionEvent(1, "phase", "summary", Map.of(), Instant.now());
        AgentRunContext context = AgentRunContext.builder()
                .runId("run-1").tenantId("default").agentKey("super").conversationId("conv-1")
                .agentName("worker").userPrompt("x".repeat(500)).build();
        hook.beforeRun(context);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(traceService).record(anyString(), anyString(), anyString(), anyString(), anyString(), summary.capture(), any());
        // "开始执行：" 前缀 + 截断到 120 字符 + "..." 后缀
        Assertions.assertTrue(summary.getValue().length() <= "开始执行：".length() + ExecutionTraceHook.MAX_SUMMARY_CHARS + 3);
        Assertions.assertTrue(summary.getValue().endsWith("..."));
    }
}
