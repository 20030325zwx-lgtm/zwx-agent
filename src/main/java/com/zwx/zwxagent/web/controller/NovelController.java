package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.creative.novel.NovelChapterService;
import com.zwx.zwxagent.creative.novel.NovelPlanningService;
import com.zwx.zwxagent.creative.service.CreativeProjectService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 小说创作 API：/api/ai/creative/projects/{projectId}/novel 前缀。
 * 章节库与作品设定是创作项目的有状态内容层；剧情建议走 chat 模型，不产生章节与任务。
 */
@RestController
@RequestMapping("/ai/creative/projects/{projectId}/novel")
public class NovelController {

    private final NovelChapterService chapterService;
    private final NovelPlanningService planningService;
    private final CreativeProjectService projectService;

    public NovelController(NovelChapterService chapterService, NovelPlanningService planningService,
                           CreativeProjectService projectService) {
        this.chapterService = chapterService;
        this.planningService = planningService;
        this.projectService = projectService;
    }

    public record UpdateChapterBody(String title, String plotDirection, String content, String summary) {
    }

    public record PlotSuggestionBody(String direction) {
    }

    @GetMapping("/chapters")
    public List<NovelChapterService.ChapterView> chapters(CurrentActor actor, @PathVariable UUID projectId) {
        return chapterService.list(actor, projectId);
    }

    @PatchMapping("/chapters/{chapterId}")
    public NovelChapterService.ChapterView updateChapter(CurrentActor actor, @PathVariable UUID projectId,
                                                         @PathVariable UUID chapterId,
                                                         @RequestBody(required = false) UpdateChapterBody body) {
        return chapterService.update(actor, chapterId, body == null ? null
                : new NovelChapterService.UpdateChapterRequest(body.title(), body.plotDirection(),
                        body.content(), body.summary()));
    }

    @PostMapping("/chapters/{chapterId}/confirm")
    public NovelChapterService.ChapterView confirmChapter(CurrentActor actor, @PathVariable UUID projectId,
                                                          @PathVariable UUID chapterId) {
        return chapterService.confirm(actor, chapterId);
    }

    @DeleteMapping("/chapters/{chapterId}")
    public Map<String, Object> deleteChapter(CurrentActor actor, @PathVariable UUID projectId,
                                             @PathVariable UUID chapterId) {
        chapterService.delete(actor, chapterId);
        return Map.of("ok", true);
    }

    @GetMapping("/settings")
    public Map<String, Object> settings(CurrentActor actor, @PathVariable UUID projectId) {
        return projectService.settingsOf(actor, projectId);
    }

    @PutMapping("/settings")
    public Map<String, Object> updateSettings(CurrentActor actor, @PathVariable UUID projectId,
                                              @RequestBody(required = false) Map<String, Object> body) {
        return projectService.updateSettings(actor, projectId, body);
    }

    @PostMapping("/plot-suggestions")
    public NovelPlanningService.SuggestionsView plotSuggestions(CurrentActor actor, @PathVariable UUID projectId,
                                                                @RequestBody(required = false) PlotSuggestionBody body) {
        return planningService.suggest(actor, projectId, body == null ? null : body.direction());
    }
}
