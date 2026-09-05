package com.zwx.zwxagent.agent.graph;

import org.springframework.ai.tool.ToolCallback;

import java.util.function.BooleanSupplier;

/**
 * 一次图运行的上下文：跟随 OverAllState 传递，但不参与图状态合并语义。
 */
public record RunContext(ToolCallback[] tools,
                         String knowledgeContext,
                         BooleanSupplier stopCheck) {

    public static RunContext of(ToolCallback[] tools, String knowledgeContext, BooleanSupplier stopCheck) {
        return new RunContext(tools, knowledgeContext == null ? "" : knowledgeContext, stopCheck);
    }
}
