package com.zwx.zwxagent.creative.domain;

/**
 * 剧情连续性记录类型（P2）：FACT 已确认事实、STATE 人物与场景状态、FORESHADOW 伏笔。
 * AI 可建议，确认由作者完成；未确认草稿不得自动成为其他剧集的既定事实。
 */
public enum ContinuityNoteKind {
    FACT,
    STATE,
    FORESHADOW
}
