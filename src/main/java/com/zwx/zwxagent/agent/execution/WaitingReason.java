package com.zwx.zwxagent.agent.execution;

/** 等人原因：面板/任务列表据此说明"在等谁做什么"。 */
public enum WaitingReason {
    /** 多步计划已生成，等用户确认后执行 */
    CONFIRM_PLAN,
    /** 有副作用工具待确认 */
    CONFIRM_TOOL,
    /** 智能体通过 askHuman 提问，等用户补充信息 */
    NEED_INPUT;

    public static WaitingReason from(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public String label() {
        return switch (this) {
            case CONFIRM_PLAN -> "等待确认执行计划";
            case CONFIRM_TOOL -> "等待确认工具操作";
            case NEED_INPUT -> "等待补充信息";
        };
    }
}
