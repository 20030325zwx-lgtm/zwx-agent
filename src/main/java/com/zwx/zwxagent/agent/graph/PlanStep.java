package com.zwx.zwxagent.agent.graph;

import java.util.List;

/**
 * 计划中的一个子任务。
 * id 供依赖引用；dependsOn 是前置步骤 id（波次执行：无依赖的并行，有依赖的等上游结论）；
 * acceptance 是可验证的完成判据，供质检门禁与模型评价使用。
 */
public record PlanStep(String id, String title, WorkerRole role, String detail,
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

    /** 兼容旧调用的三参构造：无 id、无依赖、无验收判据。 */
    public PlanStep(String title, WorkerRole role, String detail) {
        this("", title, role, detail, List.of(), "");
    }

    /** 五参构造：带 id 与依赖（验收判据留空）。 */
    public PlanStep(String id, String title, WorkerRole role, String detail, List<String> dependsOn) {
        this(id, title, role, detail, dependsOn, "");
    }
}
