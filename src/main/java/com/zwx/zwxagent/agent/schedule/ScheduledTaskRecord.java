package com.zwx.zwxagent.agent.schedule;

import java.time.Instant;
import java.time.LocalTime;

/**
 * 定时任务定义：到点以 {@link #instruction} 在任务专属会话里拉起一次智能体运行。
 * 调度字段只支持 DAILY/WEEKLY/ONCE 三种；next_run_at 预计算落库，调度器零时间语义。
 */
public record ScheduledTaskRecord(String id, String tenantId, String userId,
                                  String name, String instruction,
                                  String conversationId, boolean sessionContinuity,
                                  String permissionMode, Long modelRowId,
                                  String scheduleKind, LocalTime runTime, Integer weeklyDow, Instant onceAt,
                                  boolean enabled, Instant lastRunAt, Instant nextRunAt,
                                  Instant createdAt, Instant updatedAt) {

    public ScheduleTimes.Schedule schedule() {
        return new ScheduleTimes.Schedule(scheduleKind, runTime, weeklyDow, onceAt);
    }

    public boolean once() {
        return "ONCE".equals(scheduleKind);
    }
}
