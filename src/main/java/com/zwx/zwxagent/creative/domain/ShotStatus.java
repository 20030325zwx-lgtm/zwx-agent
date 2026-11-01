package com.zwx.zwxagent.creative.domain;

/**
 * 镜头生产状态（P1/P2）：内容字段是否就绪与生产进度分开表达。
 * DRAFT 字段未就绪 → READY 可生成 → GENERATING 在途 → PENDING_SELECTION 待选片 → SELECTED 已选定；
 * 生成失败回 READY，换选自动在候选层处理。
 */
public enum ShotStatus {
    DRAFT,
    READY,
    GENERATING,
    PENDING_SELECTION,
    SELECTED;

    /** 是否允许发起新的生成（在途/待选片/已选定时不重复发起）。 */
    public boolean canGenerate() {
        return this == DRAFT || this == READY;
    }
}
