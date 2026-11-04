package com.zwx.zwxagent.agent.execution;

/**
 * 规范运行阶段（对齐 dsh-superagent 的 Ticket 阶段模型）。
 *
 * <p>{@code agent_run.status} 保留为执行器内部状态（兼容旧逻辑），对外展示与分组一律用 phase：
 * 状态标签写"在等谁做什么"，而不是内部枚举。第一匹配即结论：</p>
 * <pre>
 * WAITING_HUMAN  等人（确认计划 / 确认工具 / 补充信息）
 * WORKING        在干活，或本轮已结束但后台任务仍未完成（background_jobs &gt; 0）
 * BLOCKED        被卡住（后台任务超时、配额拒绝等需要人处理）
 * FAILED         失败
 * DONE           完成
 * STOPPED        人主动停止
 * QUEUED         已创建未开始
 * </pre>
 */
public enum RunPhase {
    QUEUED,
    WORKING,
    WAITING_HUMAN,
    BLOCKED,
    FAILED,
    DONE,
    STOPPED;

    /** 由执行器状态 + 等待原因 + 后台任务数推导阶段（纯函数，便于单测）。 */
    public static RunPhase derive(String status, String waitingReason, int backgroundJobs) {
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        boolean waiting = waitingReason != null && !waitingReason.isBlank();
        return switch (normalizedStatus) {
            case "COMPLETED" -> backgroundJobs > 0 ? WORKING : DONE;
            case "FAILED" -> FAILED;
            case "INTERRUPTED" -> waiting ? WAITING_HUMAN : STOPPED;
            case "RUNNING" -> WORKING;
            default -> QUEUED;
        };
    }

    /** 是否仍在占用执行槽位（面板用：working 的面板项不需要人）。 */
    public boolean active() {
        return this == WORKING || this == WAITING_HUMAN;
    }
}
