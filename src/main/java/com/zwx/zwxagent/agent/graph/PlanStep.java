package com.zwx.zwxagent.agent.graph;

import java.util.List;

/**
 * 计划中的一个子任务。
 */
public record PlanStep(String title, WorkerRole role, String detail) {

    public PlanStep {
        title = title == null || title.isBlank() ? "子任务" : title.trim();
        detail = detail == null ? "" : detail.trim();
    }
}
