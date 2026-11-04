package com.zwx.zwxagent.platform.workspace;

import com.zwx.zwxagent.capability.tools.ToolSandbox;
import com.zwx.zwxagent.platform.desktop.DesktopDirectoryGrantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 把会话工作区里的产物导出到用户授权的本机目录（仅桌面端；目录必须在授权清单里）。
 *
 * <p>桌面端后端跑在本机，但浏览器下载只能落到默认下载目录；这个服务给「保存到文件夹」提供
 * 服务端落盘能力，返回真实绝对路径，便于桌面端做「在访达/资源管理器中显示」。</p>
 */
@Service
public class WorkspaceFileExportService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceFileExportService.class);

    public record ExportResult(String from, String to) {
    }

    private final WorkspaceService workspaceService;
    private final ToolSandbox toolSandbox;
    private final DesktopDirectoryGrantService grantService;

    public WorkspaceFileExportService(WorkspaceService workspaceService, ToolSandbox toolSandbox,
                                      DesktopDirectoryGrantService grantService) {
        this.workspaceService = workspaceService;
        this.toolSandbox = toolSandbox;
        this.grantService = grantService;
    }

    public List<ExportResult> export(String tenantId, String agentKey, String conversationId,
                                     List<String> paths, String targetDir, boolean overwrite) throws IOException {
        if (!grantService.active()) {
            throw new IllegalStateException("目录导出仅在桌面端可用");
        }
        if (paths == null || paths.isEmpty()) {
            throw new IllegalArgumentException("paths 不能为空");
        }
        if (paths.size() > 50) {
            throw new IllegalArgumentException("一次最多导出 50 个文件");
        }
        if (targetDir == null || targetDir.isBlank()) {
            throw new IllegalArgumentException("targetDir 不能为空");
        }
        Path directory = Path.of(targetDir).toAbsolutePath().normalize();
        grantService.requireGranted(directory.toString());
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("目标目录不存在：" + directory);
        }
        List<ExportResult> results = new ArrayList<>(paths.size());
        for (String relative : paths) {
            Path source = resolveSource(tenantId, agentKey, conversationId, relative);
            if (source == null) {
                throw new IllegalArgumentException("文件不存在：" + relative);
            }
            Path target = uniqueTarget(directory, source.getFileName().toString(), overwrite);
            if (overwrite) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            } else {
                Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
            results.add(new ExportResult(relative, target.toString()));
        }
        log.info("Exported {} workspace file(s) of conversation {} to {}", results.size(), conversationId, directory);
        return results;
    }

    /** 与 /ai/manus/files 一致：优先统一工作区，回退旧工具目录。 */
    private Path resolveSource(String tenantId, String agentKey, String conversationId, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        try {
            Path workspaceRoot = workspaceService.conversationRoot(tenantId, agentKey, conversationId);
            Path file = workspaceService.resolveWithin(workspaceRoot, relativePath);
            if (Files.isRegularFile(file)) return file;
        } catch (Exception ignored) {
        }
        try {
            Path legacyRoot = toolSandbox.scopeDir(conversationId);
            Path file = legacyRoot.resolve(relativePath).normalize();
            if (file.startsWith(legacyRoot) && Files.isRegularFile(file)) return file;
        } catch (Exception ignored) {
        }
        return null;
    }

    private Path uniqueTarget(Path directory, String fileName, boolean overwrite) {
        Path target = directory.resolve(fileName).normalize();
        if (!target.startsWith(directory)) {
            throw new IllegalArgumentException("非法文件名：" + fileName);
        }
        if (overwrite || !Files.exists(target)) return target;
        String base = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        int index = 1;
        Path candidate = directory.resolve(base + " (" + index + ")" + extension);
        while (Files.exists(candidate)) {
            index += 1;
            candidate = directory.resolve(base + " (" + index + ")" + extension);
        }
        return candidate;
    }
}
