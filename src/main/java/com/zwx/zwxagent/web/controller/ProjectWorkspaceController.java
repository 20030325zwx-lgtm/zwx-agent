package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.workspace.ProjectWorkspace;
import com.zwx.zwxagent.platform.workspace.ProjectWorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/ai/projects")
public class ProjectWorkspaceController {

    private final ProjectWorkspaceService projectWorkspaceService;

    public ProjectWorkspaceController(ProjectWorkspaceService projectWorkspaceService) {
        this.projectWorkspaceService = projectWorkspaceService;
    }

    @GetMapping
    public List<ProjectWorkspace> list(CurrentActor actor) {
        return projectWorkspaceService.list(actor.tenantId(), actor.userId());
    }

    @PostMapping
    public ProjectWorkspace create(CurrentActor actor, @RequestBody CreateProjectRequest request) {
        return projectWorkspaceService.create(actor.tenantId(), actor.userId(), request.name(), request.directoryName());
    }

    public record CreateProjectRequest(String name, String directoryName) {
    }
}
