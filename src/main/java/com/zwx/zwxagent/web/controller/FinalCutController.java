package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.creative.domain.VideoFinalCutRecord;
import com.zwx.zwxagent.creative.story.FinalCutService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
 * 成片 API（P2 最小闭环）：草稿重建/编辑、导出提交（幂等）、取消/重试、历史与产物下载地址。
 * 产物登记为 DERIVED 素材，播放/下载复用统一素材链路（签名 URL），不重新签名渲染。
 */
@RestController
@RequestMapping("/ai/creative/projects/{projectId}/final-cuts")
public class FinalCutController {

    private final FinalCutService finalCutService;

    public FinalCutController(FinalCutService finalCutService) {
        this.finalCutService = finalCutService;
    }

    /** 重建草稿：按镜头顺序取每镜已采用候选；返回缺片清单（不阻止草稿创建）。 */
    @PostMapping("/draft")
    public FinalCutService.DraftView rebuildDraft(CurrentActor actor, @PathVariable UUID projectId) {
        return finalCutService.buildDraft(actor, projectId);
    }

    @GetMapping("/draft")
    public FinalCutService.DraftView draft(CurrentActor actor, @PathVariable UUID projectId) {
        return finalCutService.draft(actor, projectId);
    }

    @GetMapping
    public List<VideoFinalCutRecord> list(CurrentActor actor, @PathVariable UUID projectId,
                                          @RequestParam(defaultValue = "20") int limit) {
        return finalCutService.list(actor, projectId, limit);
    }

    @GetMapping("/{cutId}")
    public VideoFinalCutRecord get(CurrentActor actor, @PathVariable UUID projectId,
                                   @PathVariable UUID cutId) {
        return finalCutService.get(actor, projectId, cutId);
    }

    @PatchMapping("/{cutId}/items")
    public VideoFinalCutRecord updateItems(CurrentActor actor, @PathVariable UUID projectId,
                                           @PathVariable UUID cutId,
                                           @RequestBody FinalCutService.UpdateItemsRequest body) {
        return finalCutService.updateItems(actor, projectId, cutId, body);
    }

    @PostMapping("/{cutId}/export")
    public VideoFinalCutRecord export(CurrentActor actor, @PathVariable UUID projectId,
                                      @PathVariable UUID cutId,
                                      @RequestBody(required = false) FinalCutService.ExportRequest body) {
        return finalCutService.export(actor, projectId, cutId, body);
    }

    @PostMapping("/{cutId}/cancel")
    public VideoFinalCutRecord cancel(CurrentActor actor, @PathVariable UUID projectId,
                                      @PathVariable UUID cutId) {
        return finalCutService.cancel(actor, projectId, cutId);
    }

    @PostMapping("/{cutId}/retry")
    public VideoFinalCutRecord retry(CurrentActor actor, @PathVariable UUID projectId,
                                     @PathVariable UUID cutId) {
        return finalCutService.retry(actor, projectId, cutId);
    }

    @GetMapping("/{cutId}/output")
    public Map<String, Object> output(CurrentActor actor, @PathVariable UUID projectId,
                                      @PathVariable UUID cutId) {
        return finalCutService.outputAsset(actor, projectId, cutId)
                .<Map<String, Object>>map(asset -> Map.of(
                        "assetId", String.valueOf(asset.id()),
                        "displayName", asset.displayName(),
                        "durationMs", asset.durationMs() == null ? 0 : asset.durationMs(),
                        "byteSize", asset.byteSize()))
                .orElseGet(() -> Map.of("assetId", ""));
    }
}
