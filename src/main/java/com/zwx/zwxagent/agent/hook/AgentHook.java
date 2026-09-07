package com.zwx.zwxagent.agent.hook;

import com.zwx.zwxagent.agent.BaseAgent;

import java.util.List;

/**
 * Agent 执行链路的可插拔回调。实现为 Spring Bean 自动注册；order 越小越先执行。
 * 观察型：只读；转换型：修改传入参数；拦截型：抛 {@link HookAbortException}。
 */
public interface AgentHook {

    /** 唯一标识，用于去重与排障。 */
    String id();

    /** 越小越先执行。 */
    int order();

    default void beforeRun(AgentRunContext context) {
    }

    default void beforeStep(AgentRunContext context) {
    }

    default void beforeThink(AgentRunContext context) {
    }

    default void afterThink(AgentRunContext context, boolean toolCallPlanned) {
    }

    /** 可修改或移除计划中的工具调用；抛 HookAbortException 则本 step 受控短路。 */
    default void beforeToolCalls(AgentRunContext context, List<PlannedToolCall> plannedCalls) {
    }

    default void afterToolCalls(AgentRunContext context, List<BaseAgent.ToolExecution> executions) {
    }

    default void afterStep(AgentRunContext context, String stepResult) {
    }

    default void onFinish(AgentRunContext context) {
    }

    default void onInterrupted(AgentRunContext context, String reason) {
    }

    default void onError(AgentRunContext context, Exception error) {
    }

    default void afterRun(AgentRunContext context) {
    }
}
