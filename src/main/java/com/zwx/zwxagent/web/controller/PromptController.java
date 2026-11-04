package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.agent.execution.PromptVersionService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提示词版本管理：查询当前生效版本 / 历史版本 / 保存新版本（乐观并发：baseVersion 必须等于当前版本）。
 * 版本 0 表示内置默认内容。
 */
@RestController
@RequestMapping("/ai/prompts")
public class PromptController {

    @Resource
    private PromptVersionService promptVersionService;

    public record SaveRequest(String content, Integer baseVersion, String note) {
    }

    @GetMapping
    public List<PromptVersionService.ActivePrompt> list(CurrentActor actor) {
        return PromptVersionService.KEYS.stream()
                .map(key -> promptVersionService.active(key, ""))
                .toList();
    }

    @GetMapping("/{key}/versions")
    public List<PromptVersionService.VersionRecord> versions(CurrentActor actor, @PathVariable String key) {
        return promptVersionService.history(key);
    }

    @PostMapping("/{key}")
    public PromptVersionService.ActivePrompt save(CurrentActor actor, @PathVariable String key,
                                                  @RequestBody SaveRequest request) {
        int baseVersion = request.baseVersion() == null ? 0 : request.baseVersion();
        return promptVersionService.save(key, request.content(), baseVersion, request.note(), "panel");
    }
}
