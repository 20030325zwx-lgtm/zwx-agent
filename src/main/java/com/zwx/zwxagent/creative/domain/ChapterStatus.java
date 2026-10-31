package com.zwx.zwxagent.creative.domain;

/**
 * 小说章节状态：工作流 novel_chapter_save 写入为 DRAFT；
 * 创作台确认入库后为 CONFIRMED（概要进入后续章节的生成上下文，不可删除）。
 */
public enum ChapterStatus {
    DRAFT,
    CONFIRMED
}
