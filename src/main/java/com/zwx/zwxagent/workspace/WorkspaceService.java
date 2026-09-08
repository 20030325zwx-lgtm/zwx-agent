package com.zwx.zwxagent.workspace;

import com.zwx.zwxagent.constant.FileConstant;
import com.zwx.zwxagent.tools.ToolSandbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 统一工作区（design/plans/09 批次 A）：
 * {root}/{tenantId}/{agentKey}/{conversationId}/ 为会话工作区（工具 workDir），
 * {root}/{tenantId}/{agentKey}/shared/ 为跨会话共享目录。
 * 路径守卫复用 ToolSandbox.resolveWithin（穿越/符号链接拒绝）。
 */
@Component
public class WorkspaceService {

    public record FileEntry(String path, long size, Instant modifiedAt) {
    }

    private final ToolSandbox sandbox;
    private final Path root;

    public WorkspaceService(ToolSandbox sandbox,
                            @Value("${app.workspace.root:}") String configuredRoot) {
        this.sandbox = sandbox;
        this.root = configuredRoot == null || configuredRoot.isBlank()
                ? Path.of(FileConstant.FILE_SAVE_DIR, "workspaces").toAbsolutePath().normalize()
                : Path.of(configuredRoot).toAbsolutePath().normalize();
    }

    /** 会话工作区根（工具 workDir），自动创建。 */
    public Path conversationRoot(String tenantId, String agentKey, String conversationId) {
        requireComponent(tenantId, "tenantId");
        requireComponent(agentKey, "agentKey");
        requireComponent(conversationId, "conversationId");
        Path dir = root.resolve(sanitize(tenantId)).resolve(sanitize(agentKey)).resolve(sanitize(conversationId))
                .normalize();
        if (!dir.startsWith(root)) throw new IllegalArgumentException("Invalid workspace scope");
        return ensure(dir);
    }

    /** 跨会话共享目录（同一租户同一智能体），自动创建。 */
    public Path sharedDir(String tenantId, String agentKey) {
        requireComponent(tenantId, "tenantId");
        requireComponent(agentKey, "agentKey");
        return ensure(root.resolve(sanitize(tenantId)).resolve(sanitize(agentKey)).resolve("shared").normalize());
    }

    /** 相对路径解析守卫（穿越/符号链接拒绝），复用 ToolSandbox 逻辑。 */
    public Path resolveWithin(Path base, String relativeName) {
        return sandbox.resolveWithin(base, relativeName);
    }

    /** 会话工作区文件清单（相对路径，深度优先，最多 maxEntries 条）。 */
    public List<FileEntry> listFiles(String tenantId, String agentKey, String conversationId, int maxEntries) {
        Path base = conversationRoot(tenantId, agentKey, conversationId);
        List<FileEntry> entries = new ArrayList<>();
        if (!Files.isDirectory(base)) return entries;
        try (Stream<Path> stream = Files.walk(base)) {
            stream.filter(Files::isRegularFile).limit(Math.max(1, maxEntries) * 2L).forEach(file -> {
                if (entries.size() >= maxEntries) return;
                try {
                    entries.add(new FileEntry(base.relativize(file).toString().replace('\\', '/'),
                            Files.size(file), Files.getLastModifiedTime(file).toInstant()));
                } catch (IOException ignored) {
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to list workspace files", exception);
        }
        entries.sort(Comparator.comparing(FileEntry::path));
        return entries;
    }

    /** 旧工具目录（temp/tools/{scope}），仅用于历史会话文件回退读取。 */
    public Path legacyScopeDir(String scope) {
        return sandbox.scopeDir(scope);
    }

    public Path root() {
        return root;
    }

    private Path ensure(Path dir) {
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create workspace directory", exception);
        }
    }

    private void requireComponent(String value, String name) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid workspace " + name);
        }
    }

    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
