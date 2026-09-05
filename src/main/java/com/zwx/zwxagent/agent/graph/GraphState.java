package com.zwx.zwxagent.agent.graph;

/**
 * 图状态键与策略注册的集中定义。
 */
final class GraphState {

    /** 用户原始请求（replace） */
    static final String INPUT = "input";
    /** 对话历史摘要（replace） */
    static final String HISTORY = "history";
    /** 计划子任务列表 List<PlanStep>（replace） */
    static final String PLAN = "plan";
    /** 工作者结果列表 List<String>（append） */
    static final String TASK_RESULTS = "task_results";
    /** 活动流水 List<String>，内部留痕与附件提取（append） */
    static final String ACTIVITIES = "activities";
    /** 面向前端的语义化事件 List<ActivityEvent>（append） */
    static final String SSE_EVENTS = "sse_events";
    /** 质检轮次（replace） */
    static final String REVISION = "revision";
    /** 质检结论 pass|revise（replace） */
    static final String VERIFICATION_STATUS = "verification_status";
    /** 质检反馈（replace） */
    static final String VERIFICATION_FEEDBACK = "verification_feedback";
    /** 最终交付答案（replace） */
    static final String FINAL_ANSWER = "final_answer";
    /** 每次运行的上下文（工具、知识、停止信号；replace） */
    static final String RUN_CONTEXT = "run_context";

    private GraphState() {
    }
}
