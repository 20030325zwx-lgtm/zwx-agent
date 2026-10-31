package com.zwx.zwxagent.creative.novel;

import com.zwx.zwxagent.creative.domain.ChapterStatus;
import com.zwx.zwxagent.creative.domain.CreativeConflictException;
import com.zwx.zwxagent.creative.domain.NovelChapterRecord;
import com.zwx.zwxagent.creative.repository.NovelChapterRepository;
import com.zwx.zwxagent.creative.service.CreativeProjectService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 小说章节生命周期（V13）：工作流 novel_chapter_save 幂等写入草稿；
 * 创作台编辑/确认。CONFIRMED 章节是后续章节上下文的一部分，不可删除。
 */
@Service
public class NovelChapterService {

    public static final int TITLE_MAX = 200;
    public static final int PLOT_DIRECTION_MAX = 4000;
    public static final int SUMMARY_MAX = 2000;
    public static final int CONTENT_MAX = 200_000;

    public record ChapterView(UUID id, UUID projectId, int chapterNumber, String title,
                              String plotDirection, String content, String summary, int wordCount,
                              String status, UUID sourceRunId, java.time.Instant createdAt,
                              java.time.Instant updatedAt) {
    }

    public record UpdateChapterRequest(String title, String plotDirection, String content, String summary) {
    }

    private final NovelChapterRepository chapterRepository;
    private final CreativeProjectService projectService;

    public NovelChapterService(NovelChapterRepository chapterRepository, CreativeProjectService projectService) {
        this.chapterRepository = chapterRepository;
        this.projectService = projectService;
    }

    public List<ChapterView> list(CurrentActor actor, UUID projectId) {
        requireProject(actor, projectId);
        return chapterRepository.listByProject(actor.tenantId(), actor.userId(), projectId)
                .stream().map(NovelChapterService::toView).toList();
    }

    public ChapterView get(CurrentActor actor, UUID chapterId) {
        return chapterRepository.findByIdForOwner(actor.tenantId(), actor.userId(), chapterId)
                .map(NovelChapterService::toView)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在"));
    }

    /** 编辑章节：字段可独立省略（null 不更新）；正文/概要长度受限。 */
    public ChapterView update(CurrentActor actor, UUID chapterId, UpdateChapterRequest request) {
        var chapter = requireChapter(actor, chapterId);
        String title = request == null || request.title() == null
                ? chapter.title() : normalize(request.title(), TITLE_MAX, "章节标题");
        String plotDirection = request == null || request.plotDirection() == null
                ? chapter.plotDirection() : normalize(request.plotDirection(), PLOT_DIRECTION_MAX, "剧情走向");
        String content = request == null || request.content() == null
                ? chapter.content() : normalize(request.content(), CONTENT_MAX, "章节正文");
        String summary = request == null || request.summary() == null
                ? chapter.summary() : normalize(request.summary(), SUMMARY_MAX, "剧情概要");
        if (!chapterRepository.updateContent(chapterId, title, plotDirection, content, summary,
                wordCount(content))) {
            throw new IllegalStateException("章节更新失败");
        }
        return get(actor, chapterId);
    }

    /** 确认入库：DRAFT → CONFIRMED；概要从此进入后续章节上下文。已确认则幂等返回。 */
    public ChapterView confirm(CurrentActor actor, UUID chapterId) {
        var chapter = requireChapter(actor, chapterId);
        if (chapter.status() == ChapterStatus.CONFIRMED) return toView(chapter);
        if (chapter.content() == null || chapter.content().isBlank()) {
            throw new IllegalArgumentException("章节正文为空，不能确认入库");
        }
        chapterRepository.updateStatus(chapterId, ChapterStatus.CONFIRMED);
        return get(actor, chapterId);
    }

    public void delete(CurrentActor actor, UUID chapterId) {
        var chapter = requireChapter(actor, chapterId);
        if (chapter.status() == ChapterStatus.CONFIRMED) {
            throw new CreativeConflictException("已确认章节不可删除（章节号不可复用）");
        }
        chapterRepository.delete(chapterId);
    }

    /**
     * 工作流 novel_chapter_save 入库（幂等）：同一运行重复入库更新既有草稿，不产生重复章节；
     * 新章节号 = 项目当前最大章号 + 1，状态 DRAFT。
     */
    public ChapterView saveFromRun(CurrentActor actor, UUID projectId, UUID runId,
                                   String title, String content, String summary, String plotDirection) {
        var project = projectService.get(actor, projectId);
        if (!"ACTIVE".equals(project.status())) {
            throw new IllegalStateException("项目已归档，不能写入章节");
        }
        String safeContent = normalize(content == null ? "" : content, CONTENT_MAX, "章节正文");
        String safeSummary = normalize(summary == null ? "" : summary, SUMMARY_MAX, "剧情概要");
        String safeDirection = normalize(plotDirection == null ? "" : plotDirection,
                PLOT_DIRECTION_MAX, "剧情走向");
        var existing = chapterRepository.findByRun(actor.tenantId(), actor.userId(), projectId, runId);
        int number = existing.map(NovelChapterRecord::chapterNumber)
                .orElseGet(() -> chapterRepository.nextChapterNumber(projectId));
        String safeTitle = title == null || title.isBlank()
                ? "第" + number + "章" : normalize(title.trim(), TITLE_MAX, "章节标题");
        if (existing.isPresent()) {
            chapterRepository.updateContent(existing.get().id(), safeTitle, safeDirection, safeContent,
                    safeSummary, wordCount(safeContent));
            return get(actor, existing.get().id());
        }
        var record = new NovelChapterRecord(UUID.randomUUID(), projectId, actor.tenantId(), actor.userId(),
                number, safeTitle, safeDirection, safeContent, safeSummary, wordCount(safeContent),
                ChapterStatus.DRAFT, runId, java.time.Instant.now(), java.time.Instant.now());
        chapterRepository.insert(record);
        return toView(record);
    }

    /** 汉字字数：去除空白后的字符数。 */
    public static int wordCount(String content) {
        return content == null ? 0 : content.replaceAll("\\s+", "").length();
    }

    private NovelChapterRecord requireChapter(CurrentActor actor, UUID chapterId) {
        return chapterRepository.findByIdForOwner(actor.tenantId(), actor.userId(), chapterId)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在"));
    }

    private void requireProject(CurrentActor actor, UUID projectId) {
        projectService.get(actor, projectId);
    }

    private static String normalize(String value, int max, String label) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(label + "超过长度上限（" + max + " 字符）");
        }
        return trimmed;
    }

    private static ChapterView toView(NovelChapterRecord record) {
        return new ChapterView(record.id(), record.projectId(), record.chapterNumber(), record.title(),
                record.plotDirection(), record.content(), record.summary(), record.wordCount(),
                record.status().name(), record.sourceRunId(), record.createdAt(), record.updatedAt());
    }
}
