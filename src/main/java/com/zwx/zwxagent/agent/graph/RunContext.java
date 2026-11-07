package com.zwx.zwxagent.agent.graph;

import org.springframework.ai.tool.ToolCallback;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 一次图运行的上下文：跟随 OverAllState 传递，但不参与图状态合并语义。
 * identity 用于 hook（执行轨迹）定位租户/会话，可空（程序内调用）。
 */
public record RunContext(ToolCallback[] tools,
                         String knowledgeContext,
                         BooleanSupplier stopCheck,
                         String tenantId,
                         String userId,
                         String conversationId,
                         Consumer<String> answerChunkSink,
                         Consumer<ActivityEvent> activitySink,
                         boolean webSearchEnabled,
                         Consumer<ThinkDeltaEvent> thinkDeltaSink) {

    public RunContext {
        if (knowledgeContext == null) knowledgeContext = "";
    }

    /** worker 思考阶段文本增量的实时出口；为空时 worker 走阻塞模型调用（评测/定时任务/程序内调用）。 */
    public RunContext withThinkDeltaSink(Consumer<ThinkDeltaEvent> thinkDeltaSink) {
        return new RunContext(tools, knowledgeContext, stopCheck, tenantId, userId, conversationId,
                answerChunkSink, activitySink, webSearchEnabled, thinkDeltaSink);
    }

    public static RunContext of(ToolCallback[] tools, String knowledgeContext, BooleanSupplier stopCheck) {
        return new RunContext(tools, knowledgeContext, stopCheck, null, null, null, null, null, true, null);
    }

    public static RunContext of(ToolCallback[] tools, String knowledgeContext, BooleanSupplier stopCheck,
                                String tenantId, String conversationId) {
        return new RunContext(tools, knowledgeContext, stopCheck, tenantId, null, conversationId, null, null, true, null);
    }

    public static RunContext of(ToolCallback[] tools, String knowledgeContext, BooleanSupplier stopCheck,
                                String tenantId, String conversationId, Consumer<String> answerChunkSink) {
        return new RunContext(tools, knowledgeContext, stopCheck, tenantId, null, conversationId, answerChunkSink, null, true, null);
    }

    public static RunContext of(ToolCallback[] tools, String knowledgeContext, BooleanSupplier stopCheck,
                                String tenantId, String userId, String conversationId, Consumer<String> answerChunkSink) {
        return new RunContext(tools, knowledgeContext, stopCheck, tenantId, userId, conversationId, answerChunkSink, null, true, null);
    }

    public static RunContext of(ToolCallback[] tools, String knowledgeContext, BooleanSupplier stopCheck,
                                String tenantId, String userId, String conversationId, Consumer<String> answerChunkSink,
                                Consumer<ActivityEvent> activitySink, boolean webSearchEnabled) {
        return new RunContext(tools, knowledgeContext, stopCheck, tenantId, userId, conversationId, answerChunkSink,
                activitySink, webSearchEnabled, null);
    }

    public static RunContext of(ToolCallback[] tools, String knowledgeContext, BooleanSupplier stopCheck,
                                String tenantId, String conversationId, Consumer<String> answerChunkSink,
                                Consumer<ActivityEvent> activitySink) {
        return new RunContext(tools, knowledgeContext, stopCheck, tenantId, null, conversationId, answerChunkSink, activitySink, true, null);
    }

    public static RunContext of(ToolCallback[] tools, String knowledgeContext, BooleanSupplier stopCheck,
                                String tenantId, String conversationId, Consumer<String> answerChunkSink,
                                Consumer<ActivityEvent> activitySink, boolean webSearchEnabled) {
        return new RunContext(tools, knowledgeContext, stopCheck, tenantId, null, conversationId, answerChunkSink,
                activitySink, webSearchEnabled, null);
    }
}
