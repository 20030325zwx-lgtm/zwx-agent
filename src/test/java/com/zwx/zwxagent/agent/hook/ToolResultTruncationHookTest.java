package com.zwx.zwxagent.agent.hook;

import com.zwx.zwxagent.agent.BaseAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class ToolResultTruncationHookTest {

    private final ToolResultTruncationHook hook = new ToolResultTruncationHook(8000);

    @Test
    void shortResultsStayUntouched() {
        List<BaseAgent.ToolExecution> executions = new ArrayList<>(
                List.of(new BaseAgent.ToolExecution("web", "{}", "短结果")));
        hook.afterToolCalls(context(), executions);
        Assertions.assertEquals("短结果", executions.get(0).result());
    }

    @Test
    void longResultsAreTruncatedWithMarker() {
        String original = "x".repeat(20_000);
        List<BaseAgent.ToolExecution> executions = new ArrayList<>(
                List.of(new BaseAgent.ToolExecution("web", "{}", original)));
        hook.afterToolCalls(context(), executions);
        String truncated = executions.get(0).result();
        Assertions.assertTrue(truncated.length() <= 8000 + 100);
        Assertions.assertTrue(truncated.startsWith("x"));
        Assertions.assertTrue(truncated.contains("原始长度 20000 字符"));
        Assertions.assertNotEquals(original, truncated);
    }

    private AgentRunContext context() {
        return AgentRunContext.builder().agentName("worker").userPrompt("hello").build();
    }
}
