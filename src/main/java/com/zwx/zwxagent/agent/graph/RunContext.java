package com.zwx.zwxagent.agent.graph;

import org.springframework.ai.tool.ToolCallback;

import java.util.function.BooleanSupplier;

/**
 * 一次图运行的上下文：跟随 OverAllState 传递，但不参与图状态合并语义。
 * identity 用于 hook（执行轨迹）定位租户/会话，可空（程序内调用）。
 */
public record RunContext(ToolCallback[] tools,
                         String knowledgeContext,
                         BooleanSupplier stopCheck,
                         String tenantId,
                         String conversationId) {

    public RunContext {
        if (knowledgeContext == null) knowledgeContext = "";
    }

    public static RunContext of(ToolCallback[] tools, String knowledgeContext, BooleanSupplier stopCheck) {
        return new RunContext(tools, knowledgeContext, stopCheck, null, null);
    }

    public static RunContext of(ToolCallback[] tools, String knowledgeContext, BooleanSupplier stopCheck,
                                String tenantId, String conversationId) {
        return new RunContext(tools, knowledgeContext, stopCheck, tenantId, conversationId);
    }
}
