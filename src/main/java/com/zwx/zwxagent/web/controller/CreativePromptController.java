package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.creative.service.PromptOptimizationService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提示词优化 API（C09）：只返回改写结果，不创建任务、不触发图片生成。
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
        return optimizationService.optimize(actor.userId(), body == null ? null : body.prompt());
    }
}
