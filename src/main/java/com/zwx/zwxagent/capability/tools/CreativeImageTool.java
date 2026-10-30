package com.zwx.zwxagent.capability.tools;

import com.zwx.zwxagent.creative.service.CreativeProjectService;
import com.zwx.zwxagent.creative.service.MediaJobService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.security.Role;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 智能体图片创作工具（C12/A31）：受理任务后立即返回 jobId，不在工具内轮询等待；
 * 结果统一进入 AI 创作用户素材库。同一 run 的重复工具调用由 IdempotentToolCallback 复用账本结果。
 */
public class CreativeImageTool {

    private static final String DEFAULT_PROJECT_NAME = "智能体创作";

    private final CurrentActor actor;
    private final MediaJobService jobService;
    private final CreativeProjectService projectService;
    private final String runId;

    public CreativeImageTool(String tenantId, String userId, String runId,
                             MediaJobService jobService, CreativeProjectService projectService) {
        this.actor = new CurrentActor(userId, tenantId, userId, Role.USER);
        this.jobService = jobService;
        this.projectService = projectService;
        this.runId = runId;
    }

    @Tool(description = """
            Generate an image with the user's configured image model. The task runs asynchronously:
            this returns a job id immediately; tell the user the result will appear in the AI 创作
            asset library. Do not call this repeatedly to poll for the result.""")
    public String generateImage(
            @ToolParam(description = "Image prompt in the user's language, at most 500 characters") String prompt,
            @ToolParam(description = "Optional size such as 1024*1024, 1280*720 or 720*1280, "
                    + "omit to use 1024*1024") String size,
            @ToolParam(description = "Optional creative project id; omit to use the agent project") String projectId) {
        if (prompt == null || prompt.isBlank()) {
            return error("PROMPT_REQUIRED", "缺少图片描述");
        }
        if (prompt.length() > 500) {
            return error("PROMPT_TOO_LONG", "提示词超过模型上限 500 个字符");
        }
        UUID project;
        try {
            project = projectId == null || projectId.isBlank()
                    ? ensureDefaultProject() : UUID.fromString(projectId.trim());
        } catch (IllegalArgumentException exception) {
            return error("INVALID_PROJECT", "项目 ID 格式不正确");
        }
        Map<String, Object> parameters = size == null || size.isBlank()
                ? Map.of() : Map.of("size", size.trim());
        try {
            var result = jobService.create(actor, "agent-" + UUID.randomUUID(), project,
                    new MediaJobService.CreateJobRequest(project, "text_to_image", null,
                            prompt, prompt, parameters, List.of(), null, null, false),
                    com.zwx.zwxagent.creative.domain.JobSourceType.AGENT, runId, null);
            return "{\"jobId\":\"" + result.jobId() + "\",\"status\":\"" + result.status()
                    + "\",\"projectId\":\"" + project
                    + "\",\"message\":\"任务已受理，图片完成后会出现在 AI 创作素材库\"}";
        } catch (RuntimeException exception) {
            return error("GENERATION_REJECTED", exception.getMessage());
        }
    }

    /** 无项目时创建固定的智能体项目，保证结果进入同一素材库且用户可见。 */
    private UUID ensureDefaultProject() {
        var existing = projectService.list(actor, null, 50).items().stream()
                .filter(project -> "ACTIVE".equals(project.status()))
                .findFirst();
        if (existing.isPresent()) return existing.get().id();
        return projectService.create(actor,
                new CreativeProjectService.CreateProjectRequest(DEFAULT_PROJECT_NAME, "由超级智能体自动创建")).id();
    }

    private static String error(String code, String message) {
        String safe = message == null ? "" : message.replace("\"", "'");
        return "{\"error\":\"" + code + "\",\"message\":\"" + safe + "\"}";
    }
}
