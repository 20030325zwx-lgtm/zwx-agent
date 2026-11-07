package com.zwx.zwxagent.agent.hook;

import com.zwx.zwxagent.agent.core.BaseAgent;
import com.zwx.zwxagent.agent.execution.store.RunEventLog;
import com.zwx.zwxagent.capability.tools.ToolCallWorkerContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件日志 hook：把 run / step / tool / 模型重试四级事件写入 run_event（append-only 真相层）。
 * <p>
 * 配对约定：beforeToolCalls 按序登记 callId（以 runId+workerKey 隔离队列），afterToolCalls
 * 按同序消费——Spring AI 的 ToolResponseMessage 与计划调用顺序一致。正常流配对由内存队列完成；
 * 崩溃后的未闭合尾部由 {@link RunEventLog#repairUnclosedToolCalls} 扫描日志合成修复事件。
 * 无归属身份（程序内调用、单测）或写库失败时静默跳过，绝不影响 agent 主流程。
 */
@Slf4j
@Component
public class RunEventLogHook implements AgentHook {

    private final RunEventLog runEventLog;
    private final Map<String, Deque<String>> pendingCalls = new ConcurrentHashMap<>();

    public RunEventLogHook(RunEventLog runEventLog) {
        this.runEventLog = runEventLog;
    }

    @Override
    public String id() {
        return "run-event-log";
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public void beforeRun(AgentRunContext context) {
        append(context, RunEventLog.RUN_START, Map.of(
                "agentName", nullSafe(context.agentName()),
                "agentKey", nullSafe(context.agentKey()),
                "userPromptChars", context.userPrompt() == null ? 0 : context.userPrompt().length()));
    }

    @Override
    public void beforeStep(AgentRunContext context) {
        append(context, RunEventLog.STEP_START, Map.of(
                "agentName", nullSafe(context.agentName()),
                "step", context.currentStep()));
    }

    @Override
    public void afterStep(AgentRunContext context, String stepResult) {
        append(context, RunEventLog.STEP_END, Map.of(
                "step", context.currentStep(),
                "resultChars", stepResult == null ? 0 : stepResult.length()));
    }

    @Override
    public void beforeToolCalls(AgentRunContext context, List<PlannedToolCall> plannedCalls) {
        if (plannedCalls == null || plannedCalls.isEmpty()) return;
        Deque<String> queue = pendingCalls.computeIfAbsent(pendingKey(context), key -> new ArrayDeque<>());
        for (PlannedToolCall call : plannedCalls) {
            queue.add(call.id());
            append(context, RunEventLog.TOOL_CALL, Map.of(
                    "callId", nullSafe(call.id()),
                    "tool", nullSafe(call.name()),
                    "workerKey", nullSafe(ToolCallWorkerContext.current()),
                    "argumentChars", call.arguments() == null ? 0 : call.arguments().length()));
        }
    }

    @Override
    public void afterToolCalls(AgentRunContext context, List<BaseAgent.ToolExecution> executions) {
        if (executions == null || executions.isEmpty()) return;
        Deque<String> queue = pendingCalls.get(pendingKey(context));
        for (BaseAgent.ToolExecution execution : executions) {
            String callId = queue == null ? "" : queue.poll();
            append(context, RunEventLog.TOOL_RESULT, Map.of(
                    "callId", callId == null ? "" : callId,
                    "tool", nullSafe(execution.name()),
                    "resultChars", execution.result() == null ? 0 : execution.result().length()));
        }
    }

    @Override
    public void onThinkRetry(AgentRunContext context, Exception error) {
        append(context, RunEventLog.ASSISTANT_ATTEMPT, Map.of(
                "step", context.currentStep(),
                "error", error == null ? "" : String.valueOf(error.getMessage())));
    }

    @Override
    public void onFinish(AgentRunContext context) {
        append(context, RunEventLog.RUN_END, Map.of(
                "reason", "FINISHED",
                "steps", context.currentStep()));
        pendingCalls.remove(pendingKey(context));
    }

    @Override
    public void onInterrupted(AgentRunContext context, String reason) {
        append(context, RunEventLog.RUN_END, Map.of(
                "reason", "INTERRUPTED",
                "detail", nullSafe(reason),
                "steps", context.currentStep()));
        pendingCalls.remove(pendingKey(context));
    }

    @Override
    public void onError(AgentRunContext context, Exception error) {
        append(context, RunEventLog.RUN_END, Map.of(
                "reason", "ERROR",
                "detail", error == null ? "" : String.valueOf(error.getMessage()),
                "steps", context.currentStep()));
        pendingCalls.remove(pendingKey(context));
    }

    private String pendingKey(AgentRunContext context) {
        return context.runId() + "|" + nullSafe(ToolCallWorkerContext.current());
    }

    private void append(AgentRunContext context, String type, Map<String, Object> data) {
        if (runEventLog == null) return;
        // 归属身份缺省（程序内调用/单测）时 append 内部自动跳过
        runEventLog.append(context.runId(), context.tenantId(), context.userId(), type, data);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
