package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.creative.skill.CreativeSkillService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 创作 Skill API（skill 设计文档 §8.4 草案的 MVP 子集）。
 * 管理端技能配置在 /ai/skills/*（SkillAdminController）；本控制器面向创作者目录与运行。
 * 方案生成不产生费用；确认执行才创建 MediaJob（沿用既有计费/重试语义）。
 */
@RestController
@RequestMapping("/ai/creative")
public class CreativeSkillController {

    private final CreativeSkillService skillService;

    public CreativeSkillController(CreativeSkillService skillService) {
        this.skillService = skillService;
    }

    public record CreateRunBody(String slug, String prompt, List<Map<String, Object>> bindings) {
    }

    public record ConfirmRunBody(String effectivePrompt, Map<String, Object> parameters) {
    }

    public record ImportPreviewBody(String markdown, String sourceRef) {
    }

    public record ImportConfirmBody(String slug) {
    }

    @GetMapping("/skills")
    public Map<String, Object> list(CurrentActor actor,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(required = false) String stage,
                                    @RequestParam(required = false) String q) {
        List<Map<String, Object>> items = skillService.list(actor).stream()
                .filter(item -> category == null || category.isBlank()
                        || category.equals(item.get("category")))
                .filter(item -> stage == null || stage.isBlank() || stage.equals(item.get("stage")))
                .filter(item -> q == null || q.isBlank() || matchesQuery(item, q))
                .toList();
        return Map.of("items", items);
    }

    private static boolean matchesQuery(Map<String, Object> item, String query) {
        String lowered = query.toLowerCase(java.util.Locale.ROOT);
        return contains(item.get("name"), lowered) || contains(item.get("summary"), lowered)
                || contains(item.get("slug"), lowered);
    }

    private static boolean contains(Object value, String lowered) {
        return value != null && String.valueOf(value).toLowerCase(java.util.Locale.ROOT).contains(lowered);
    }

    @GetMapping("/skills/{slug}")
    public Map<String, Object> detail(CurrentActor actor, @PathVariable String slug) {
        return skillService.detail(actor, slug);
    }

    @PostMapping("/projects/{projectId}/skill-runs")
    public Map<String, Object> createRun(CurrentActor actor, @PathVariable UUID projectId,
                                         @RequestBody CreateRunBody body) {
        if (body == null || body.slug() == null || body.slug().isBlank()) {
            throw new IllegalArgumentException("缺少 slug");
        }
        return skillService.createRun(actor, projectId, body.slug(), body.prompt(), body.bindings());
    }

    @GetMapping("/skill-runs/{runId}")
    public Map<String, Object> getRun(CurrentActor actor, @PathVariable UUID runId) {
        return skillService.getRun(actor, runId);
    }

    @PostMapping("/skill-runs/{runId}/retry-plan")
    public Map<String, Object> retryPlan(CurrentActor actor, @PathVariable UUID runId) {
        return skillService.retryPlan(actor, runId);
    }

    @PostMapping("/skill-runs/{runId}/confirm")
    public Map<String, Object> confirm(CurrentActor actor, @PathVariable UUID runId,
                                       @RequestBody(required = false) ConfirmRunBody body) {
        return skillService.confirm(actor, runId,
                body == null ? null : body.effectivePrompt(),
                body == null ? null : body.parameters());
    }

    /** 逐镜生成（P1 MVP）：方案 shots[] 每镜独立 MediaJob，失败镜头原位重试。 */
    @PostMapping("/skill-runs/{runId}/confirm-shots")
    public Map<String, Object> confirmShots(CurrentActor actor, @PathVariable UUID runId) {
        return skillService.confirmShots(actor, runId);
    }

    /** 导入预检（P0-C MVP）：只解析产生预览草稿，不进入系统目录。 */
    @PostMapping("/skills/imports")
    public Map<String, Object> previewImport(CurrentActor actor, @RequestBody ImportPreviewBody body) {
        if (body == null || body.markdown() == null || body.markdown().isBlank()) {
            throw new IllegalArgumentException("请提供 Markdown 技能内容");
        }
        return skillService.previewImport(actor, body.markdown(), body.sourceRef());
    }

    @PostMapping("/skills/imports/{importId}/confirm")
    public Map<String, Object> confirmImport(CurrentActor actor, @PathVariable UUID importId,
                                             @RequestBody(required = false) ImportConfirmBody body) {
        return skillService.confirmImport(actor, importId, body == null ? null : body.slug());
    }
}
