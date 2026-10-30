package com.zwx.zwxagent.creative.domain;

/**
 * 一次供应商交互的结果分类（总体设计 §7.2）。
 * Unknown 表示“无法确认上游是否受理/完成”，绝不等于失败，也不允许据此重发提交。
 */
public enum AttemptOutcome {
    ACCEPTED,
    COMPLETED,
    REJECTED,
    UNKNOWN
}
