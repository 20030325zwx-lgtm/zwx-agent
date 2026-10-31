package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.UUID;

/** 小说章节（V13）：工作流按 source_run_id 幂等写入草稿；创作台编辑/确认后概要进入后续章节上下文。 */
public record NovelChapterRecord(UUID id, UUID projectId, String tenantId, String userId,
                                 int chapterNumber, String title, String plotDirection,
                                 String content, String summary, int wordCount,
                                 ChapterStatus status, UUID sourceRunId,
                                 Instant createdAt, Instant updatedAt) {
}
