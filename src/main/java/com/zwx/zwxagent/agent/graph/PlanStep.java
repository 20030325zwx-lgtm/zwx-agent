package com.zwx.zwxagent.agent.graph;

import java.util.List;

/**
 * 计划子任务记录（编排层已退役，2026-11-08）：仅为老 checkpoint 的 JSON 兼容保留，
 * role 由枚举降级为原始字符串；新写入的检查点 plan 恒为空列表。
 */
public record PlanStep(String id, String title, String role, String detail,
                       List<String> dependsOn, String acceptance) {

    public PlanStep {
        String normalizedId = id == null ? "" : id.trim();
        title = title == null || title.isBlank() ? "子任务" : title.trim();
        detail = detail == null ? "" : detail.trim();
        dependsOn = dependsOn == null ? List.of()
                : dependsOn.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .filter(value -> !value.equals(normalizedId))
                        .distinct()
                        .toList();
        acceptance = acceptance == null ? "" : acceptance.trim();
        id = normalizedId;
    }


}
