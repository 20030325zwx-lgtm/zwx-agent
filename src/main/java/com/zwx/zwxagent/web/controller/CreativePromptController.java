package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.creative.service.PromptOptimizationService;
import com.zwx.zwxagent.creative.support.ImageStyleCatalog;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提示词优化 API（C09 + P0 扩展）：只返回改写结果，不创建任务、不触发生成。
 * 请求 mode：缺省 IMAGE（旧图片调用方零迁移兼容）；VIDEO_PROMPT/VIDEO_STORY/VIDEO_STORYBOARD
 * 按 projectId/episodeId/sceneId/shotId 由服务端读取上下文（不信任客户端拼装）。
 */
@RestController
@RequestMapping("/ai/creative/prompts")
public class CreativePromptController {

    private final PromptOptimizationService optimizationService;

    public CreativePromptController(PromptOptimizationService optimizationService) {
        this.optimizationService = optimizationService;
    }

    @PostMapping("/optimize")
    public PromptOptimizationService.OptimizeResult optimize(
            CurrentActor actor, @RequestBody PromptOptimizationService.OptimizeRequest body) {
        var request = body == null
                ? new PromptOptimizationService.OptimizeRequest(null, null, null, null, null, null, null, null)
                : body;
        return optimizationService.optimize(actor, request);
    }

    /** 画面风格目录（图片与视频共用）：前端风格中心的唯一来源（分组、题材、标签、是否依赖参考图）。 */
    @GetMapping({"/image-styles", "/styles"})
    public List<ImageStyleCatalog.View> imageStyles() {
        return ImageStyleCatalog.views();
    }
}
