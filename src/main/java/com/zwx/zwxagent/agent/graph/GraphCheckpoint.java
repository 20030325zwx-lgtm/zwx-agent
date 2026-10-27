package com.zwx.zwxagent.agent.graph;

import java.util.List;

/**
 * 可持久化的图检查点：只保存恢复所需的数据，不包含 ToolCallback、SSE 连接等运行时对象。
 */
public record GraphCheckpoint(String lastNode,
                              String nextNode,
                              List<PlanStep> plan,
                              List<String> taskResults,
                              List<String> activities,
                              List<ActivityEvent> events,
                              int revision,
                              String verificationStatus,
                              String verificationFeedback,
                              String finalAnswer,
                              String partialAnswer,
                              List<Integer> reviseSteps) {

    public GraphCheckpoint {
        plan = plan == null ? List.of() : List.copyOf(plan);
        taskResults = taskResults == null ? List.of() : List.copyOf(taskResults);
        activities = activities == null ? List.of() : List.copyOf(activities);
        events = events == null ? List.of() : List.copyOf(events);
        reviseSteps = reviseSteps == null ? List.of() : List.copyOf(reviseSteps);
    }

    /** 兼容旧调用/旧库列的十一参构造：不携带点名返工步骤（恢复后按全部重做处理）。 */
    public GraphCheckpoint(String lastNode, String nextNode, List<PlanStep> plan, List<String> taskResults,
                           List<String> activities, List<ActivityEvent> events, int revision,
                           String verificationStatus, String verificationFeedback,
                           String finalAnswer, String partialAnswer) {
        this(lastNode, nextNode, plan, taskResults, activities, events, revision,
                verificationStatus, verificationFeedback, finalAnswer, partialAnswer, List.of());
    }

    public GraphCheckpoint withTaskResults(List<String> results) {
        return new GraphCheckpoint(lastNode, nextNode, plan, results, activities, events, revision,
                verificationStatus, verificationFeedback, finalAnswer, partialAnswer, reviseSteps);
    }
}
