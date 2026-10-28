package com.zwx.zwxagent.platform.desktop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 桌面本机目录授权：Electron 主进程经系统目录选择器取得用户授权，
 * 通过回环令牌把授权清单同步给后端；所有本机目录绑定与浏览都以该清单为准。
 * 仅在桌面运行（app.desktop.loopback-token 已配置）时激活；网页部署恒不激活。
 */
@Service
@Slf4j
public class DesktopDirectoryGrantService {

    private final Set<String> grants = new CopyOnWriteArraySet<>();
    private final boolean desktopMode;

    public DesktopDirectoryGrantService(
            @Value("${app.desktop.loopback-token:}") String loopbackToken) {
        this.desktopMode = loopbackToken != null && !loopbackToken.isBlank();
    }

    public boolean active() {
        return desktopMode;
    }

    public List<String> setGrants(List<String> paths) {
        grants.clear();
        if (paths != null) paths.stream().filter(path -> path != null && !path.isBlank())
                .forEach(path -> grants.add(normalize(path)));
        log.info("桌面目录授权已同步：{} 个目录", grants.size());
        return list();
    }

    public List<String> list() {
        return grants.stream().sorted(Comparator.naturalOrder()).toList();
    }

    public void requireGranted(String path) {
        if (!isGranted(path)) {
            throw new IllegalArgumentException("目录未授权：请在桌面端通过系统目录选择器重新授权该目录");
        }
    }

    public boolean isGranted(String path) {
        if (path == null || path.isBlank()) return false;
        String normalized = normalize(path);
        return grants.stream().anyMatch(grant -> normalized.equals(grant)
                || normalized.startsWith(grant + java.io.File.separator));
    }

    private static String normalize(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }
}
