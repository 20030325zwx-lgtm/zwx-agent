package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.chat.conversation.AgentConversationService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.workspace.ProjectCodeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会话级代码工作区 API：文件树、文件内容、编辑保存、改动 diff。
 * 目标目录优先取 run checkout（能看到智能体本次改动），否则取绑定项目根目录。
 */
@RestController
@RequestMapping("/ai/manus/conversations/{conversationId}/code")
public class ProjectCodeController {

    private static final String SUPER_AGENT_KEY = "super";

    private final AgentConversationService conversationService;
    private final ProjectCodeService codeService;

    public ProjectCodeController(AgentConversationService conversationService, ProjectCodeService codeService) {
        this.conversationService = conversationService;
        this.codeService = codeService;
    }

    public record WriteRequest(String content, Long expectedModifiedAt) {
    }

    @GetMapping("/tree")
    public ProjectCodeService.TreeView tree(CurrentActor actor, @PathVariable String conversationId,
                                            @RequestParam(required = false) String path,
                                            @RequestParam(required = false) String runId) {
        requireConversation(actor, conversationId);
        return codeService.tree(actor.tenantId(), actor.userId(), conversationId, runId, path);
    }

    @GetMapping("/file")
    public ProjectCodeService.FileView file(CurrentActor actor, @PathVariable String conversationId,
                                            @RequestParam String path,
                                            @RequestParam(required = false) String runId) {
        requireConversation(actor, conversationId);
        return codeService.readFile(actor.tenantId(), actor.userId(), conversationId, runId, path);
    }

    @PutMapping("/file")
    public ProjectCodeService.FileView save(CurrentActor actor, @PathVariable String conversationId,
                                            @RequestParam String path,
                                            @RequestParam(required = false) String runId,
                                            @RequestBody WriteRequest request) {
        requireConversation(actor, conversationId);
        return codeService.writeFile(actor.tenantId(), actor.userId(), conversationId, runId,
                path, request.content(), request.expectedModifiedAt());
    }

    @GetMapping("/diff")
    public ProjectCodeService.DiffView diff(CurrentActor actor, @PathVariable String conversationId,
                                            @RequestParam(required = false) String runId) {
        requireConversation(actor, conversationId);
        return codeService.diff(actor.tenantId(), actor.userId(), conversationId, runId);
    }

    @ExceptionHandler(ProjectCodeService.ProjectFileConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(Exception exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    private void requireConversation(CurrentActor actor, String conversationId) {
        if (!conversationService.hasConversation(actor.tenantId(), actor.userId(), SUPER_AGENT_KEY, conversationId)) {
            throw new AccessDeniedException("Conversation does not belong to the current user");
        }
    }
}
