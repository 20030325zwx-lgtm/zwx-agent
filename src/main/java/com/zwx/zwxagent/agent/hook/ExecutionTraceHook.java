package com.zwx.zwxagent.agent.hook;

import com.zwx.zwxagent.agent.BaseAgent;
import com.zwx.zwxagent.execution.AgentExecutionTraceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 执行轨迹 hook：把 ReAct 链路（含 manus 图编排的 GraphWorker）的 run / step / tool
 * 三级轨迹写入 agent_execution_event 表，补齐 travel 之外智能体的可观测性。
 * 未设置归属身份（程序内调用、单测）时自动跳过；写库失败由管线隔离，绝不影响 agent 主流程。
 */
@Slf4j
@Component
public class ExecutionTraceHook implements AgentHook {

    static final int MAX_SUMMARY_CHARS = 120;
    private static final int MAX_DETAIL_CHARS = 2000;

    private final AgentExecutionTraceService traceService;

    public ExecutionTraceHook(AgentExecutionTraceService traceService) {
        this.traceService = traceService;
    }

    @Override
    public String id() {
        return "execution-trace";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public void beforeRun(AgentRunContext context) {
        recordEvent(context, "agent_started",
                "开始执行：" + compact(context.userPrompt(), MAX_SUMMARY_CHARS),
                Map.of("agentName", context.agentName()));
    }

    @Override
    public void afterStep(AgentRunContext context, String stepResult) {
        recordEvent(context, "step",
                "第 " + context.currentStep() + " 步完成：" + compact(stepResult, MAX_SUMMARY_CHARS),
                Map.of("step", context.currentStep(), "agentName", context.agentName()));
    }

    @Override
    public void afterToolCalls(AgentRunContext context, List<BaseAgent.ToolExecution> executions) {
        for (BaseAgent.ToolExecution execution : executions) {
            recordEvent(context, "tool",
                    "工具 " + execution.name() + " 执行完成",
                    Map.of("tool", execution.name(),
                            "arguments", compact(execution.arguments(), MAX_DETAIL_CHARS),
                            "result", compact(execution.result(), MAX_DETAIL_CHARS)));
        }
    }

    @Override
    public void onFinish(AgentRunContext context) {
        recordEvent(context, "agent_finished",
                "执行完成，共 " + context.currentStep() + " 步", Map.of("agentName", context.agentName()));
    }

    @Override
    public void onInterrupted(AgentRunContext context, String reason) {
        recordEvent(context, "agent_interrupted",
                "执行中断：" + compact(reason, MAX_SUMMARY_CHARS), Map.of("agentName", context.agentName()));
    }

    private void recordEvent(AgentRunContext context, String phase, String summary, Map<String, Object> detail) {
        if (context.tenantId() == null || context.agentKey() == null
                || context.conversationId() == null || context.runId() == null) {
            return;
        }
        try {
            traceService.record(context.runId(), context.tenantId(), context.agentKey(),
                    context.conversationId(), phase, summary, detail);
        } catch (Exception exception) {
            log.warn("[agent-hook] 执行轨迹记录失败（phase={}）：{}", phase, exception.getMessage());
        }
    }

    private String compact(String value, int maxChars) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "...";
    }
}
