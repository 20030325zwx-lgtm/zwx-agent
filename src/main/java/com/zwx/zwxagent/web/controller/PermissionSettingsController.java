package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.capability.tools.PermissionMode;
import com.zwx.zwxagent.capability.tools.PermissionService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 权限设置：用户默认权限模式与工具 allow/deny 规则。
 */
@RestController
@RequestMapping("/ai/settings/permission")
public class PermissionSettingsController {

    private final PermissionService permissionService;

    public PermissionSettingsController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping
    public PermissionSettings get(CurrentActor actor) {
        return new PermissionSettings(
                permissionService.defaultMode(actor.tenantId(), actor.userId()).name(),
                PermissionMode.DEFAULT.name(),
                permissionService.rules(actor.tenantId(), actor.userId()),
                PermissionMode.values());
    }

    @PutMapping
    public PermissionSettings update(CurrentActor actor, @RequestBody UpdateRequest request) {
        if (request.mode() != null && !request.mode().isBlank()) {
            permissionService.saveDefaultMode(actor.tenantId(), actor.userId(),
                    PermissionMode.require(request.mode()));
        }
        return get(actor);
    }

    @PostMapping("/rules")
    public PermissionSettings addRule(CurrentActor actor, @RequestBody RuleRequest request) {
        permissionService.addRule(actor.tenantId(), actor.userId(), request.toolPattern(), request.effect());
        return get(actor);
    }

    @DeleteMapping("/rules/{id}")
    public PermissionSettings deleteRule(CurrentActor actor, @PathVariable long id) {
        if (!permissionService.deleteRule(actor.tenantId(), actor.userId(), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found");
        }
        return get(actor);
    }

    public record UpdateRequest(String mode) {
    }

    public record RuleRequest(String toolPattern, String effect) {
    }

    public record PermissionSettings(String mode,
                                     String defaultMode,
                                     List<PermissionService.RuleView> rules,
                                     PermissionMode[] modes) {
    }
}
