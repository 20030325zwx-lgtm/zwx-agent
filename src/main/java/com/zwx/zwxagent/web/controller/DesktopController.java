package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.platform.desktop.DesktopDirectoryGrantService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 桌面本机目录授权：授权清单由 Electron 主进程经回环令牌同步；
 * 仅桌面运行时激活（网页部署返回 404，不暴露该能力）。
 */
@RestController
@RequestMapping("/ai/desktop")
public class DesktopController {

    private final DesktopDirectoryGrantService grantService;

    public DesktopController(DesktopDirectoryGrantService grantService) {
        this.grantService = grantService;
    }

    public record GrantsRequest(List<String> paths) {
    }

    public record RevokeRequest(String path) {
    }

    @PostMapping("/grants")
    public Map<String, Object> setGrants(CurrentActor actor, @RequestBody GrantsRequest request) {
        requireDesktop();
        return Map.of("grants", grantService.setGrants(request.paths()));
    }

    @GetMapping("/grants")
    public Map<String, Object> grants(CurrentActor actor) {
        requireDesktop();
        return Map.of("grants", grantService.list());
    }

    @DeleteMapping("/grants")
    public Map<String, Object> revoke(CurrentActor actor, @RequestBody RevokeRequest request) {
        requireDesktop();
        List<String> remaining = grantService.list().stream()
                .filter(grant -> !grant.equals(request.path()))
                .toList();
        grantService.setGrants(remaining);
        return Map.of("grants", remaining);
    }

    private void requireDesktop() {
        if (!grantService.active()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "桌面目录授权功能未启用");
        }
    }
}
