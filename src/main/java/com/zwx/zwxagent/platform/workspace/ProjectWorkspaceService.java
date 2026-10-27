package com.zwx.zwxagent.platform.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

@Service
public class ProjectWorkspaceService {

    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_DIRECTORY_LENGTH = 128;

    private final JdbcTemplate jdbcTemplate;
    private final Path configuredRoot;
    private final SelectionMode selectionMode;

    public ProjectWorkspaceService(JdbcTemplate jdbcTemplate,
                                   @Value("${app.project-workspace.root:${user.dir}/projects}") String root,
                                   @Value("${app.project-workspace.selection-mode:local}") String selectionMode) {
        this.jdbcTemplate = jdbcTemplate;
        this.configuredRoot = Path.of(root).toAbsolutePath().normalize();
        this.selectionMode = SelectionMode.parse(selectionMode);
    }

    public List<ProjectWorkspace> list(String tenantId, String userId) {
        return jdbcTemplate.query("""
                SELECT id, name, directory_name, path_scope, created_at, updated_at
                FROM agent_project
                WHERE tenant_id = ? AND owner_user_id = ?
                ORDER BY updated_at DESC, name ASC
                """, (rs, rowNum) -> new ProjectWorkspace(
                rs.getString("id"), rs.getString("name"), rs.getString("directory_name"), rs.getString("path_scope"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                tenantId, userId);
    }

    @Transactional
    public ProjectWorkspace create(String tenantId, String userId, String name, String directoryName) {
        String safeName = requireDisplayName(name);
        String safeDirectoryName = requireDirectoryName(directoryName);
        Path createdDirectory = createDirectChild(safeDirectoryName);
        String id = UUID.randomUUID().toString();
        try {
            jdbcTemplate.update("""
                    INSERT INTO agent_project (id, tenant_id, owner_user_id, name, directory_name, path_scope, workspace_path)
                    VALUES (?, ?, ?, ?, ?, 'ROOT', ?)
                    """, id, tenantId, userId, safeName, safeDirectoryName, safeDirectoryName);
            return jdbcTemplate.queryForObject("""
                    SELECT id, name, directory_name, path_scope, created_at, updated_at
                    FROM agent_project WHERE id = ? AND tenant_id = ? AND owner_user_id = ?
                    """, (rs, rowNum) -> new ProjectWorkspace(
                    rs.getString("id"), rs.getString("name"), rs.getString("directory_name"), rs.getString("path_scope"),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                    id, tenantId, userId);
        } catch (DataIntegrityViolationException exception) {
            deleteNewEmptyDirectory(createdDirectory);
            throw new ProjectWorkspaceConflictException("该工作区目录已被使用");
        } catch (RuntimeException exception) {
            deleteNewEmptyDirectory(createdDirectory);
            throw exception;
        }
    }

    @Transactional
    public ProjectWorkspace select(String tenantId, String userId, String path, String name) {
        ResolvedSelection selection = resolveSelection(path);
        String displayName = name == null || name.isBlank()
                ? selection.realPath().getFileName().toString()
                : requireDisplayName(name);
        String id = UUID.randomUUID().toString();
        try {
            jdbcTemplate.update("""
                    INSERT INTO agent_project
                        (id, tenant_id, owner_user_id, name, directory_name, path_scope, workspace_path)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, id, tenantId, userId, displayName, selection.realPath().getFileName().toString(),
                    selection.scope().name(), selection.storedPath());
            return jdbcTemplate.queryForObject("""
                    SELECT id, name, directory_name, path_scope, created_at, updated_at
                    FROM agent_project WHERE id = ? AND tenant_id = ? AND owner_user_id = ?
                    """, (rs, rowNum) -> new ProjectWorkspace(
                    rs.getString("id"), rs.getString("name"), rs.getString("directory_name"), rs.getString("path_scope"),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                    id, tenantId, userId);
        } catch (DataIntegrityViolationException exception) {
            throw new ProjectWorkspaceConflictException("该文件夹已经是工作区");
        }
    }

    public WorkspaceDirectoryView browse(String path) {
        try {
            Path current = resolveBrowsePath(path);
            List<WorkspaceDirectoryView.DirectoryEntry> directories;
            try (var stream = Files.list(current)) {
                directories = stream
                        .filter(candidate -> Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS))
                        .filter(candidate -> !Files.isSymbolicLink(candidate))
                        .sorted(Comparator.comparing(candidate -> candidate.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .limit(500)
                        .map(candidate -> new WorkspaceDirectoryView.DirectoryEntry(
                                candidate.getFileName().toString(), toClientPath(candidate)))
                        .toList();
            }
            Path parent = allowedParent(current);
            return new WorkspaceDirectoryView(selectionMode.name().toLowerCase(Locale.ROOT), toClientPath(current),
                    toDisplayPath(current), parent == null ? null : toClientPath(parent), isSelectable(current), directories);
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取该文件夹", exception);
        }
    }

    public boolean isOwned(String tenantId, String userId, String projectId) {
        if (projectId == null || projectId.isBlank()) return false;
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_project
                WHERE id = ? AND tenant_id = ? AND owner_user_id = ?
                """, Integer.class, projectId, tenantId, userId);
        return count != null && count > 0;
    }

    /** 解析对话绑定项目的真实目录；真实路径仅供服务端工具使用。 */
    public Optional<ResolvedProjectWorkspace> resolveForConversation(String tenantId, String userId,
                                                                     String conversationId) {
        return jdbcTemplate.query("""
                SELECT p.id, p.name, p.path_scope, p.workspace_path
                FROM agent_conversation c
                JOIN agent_project p ON p.id = c.project_id
                WHERE c.id = ? AND c.tenant_id = ? AND c.user_id = ?
                  AND p.tenant_id = ? AND p.owner_user_id = ?
                """, (rs, rowNum) -> new ResolvedProjectWorkspace(
                rs.getString("id"), rs.getString("name"),
                resolveStoredProjectPath(rs.getString("path_scope"), rs.getString("workspace_path"))),
                conversationId, tenantId, userId, tenantId, userId).stream().findFirst();
    }

    Path createDirectChild(String directoryName) {
        String safeDirectoryName = requireDirectoryName(directoryName);
        try {
            Files.createDirectories(configuredRoot);
            Path root = configuredRoot.toRealPath();
            Path candidate = root.resolve(safeDirectoryName).normalize();
            if (!candidate.startsWith(root) || !root.equals(candidate.getParent())) {
                throw new IllegalArgumentException("工作区目录必须位于项目根目录的下一级");
            }
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(candidate)) {
                    throw new IllegalArgumentException("工作区目录不能是符号链接");
                }
                throw new ProjectWorkspaceConflictException("同名目录已存在");
            }
            Files.createDirectory(candidate);
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(root) || !root.equals(realCandidate.getParent())) {
                deleteNewEmptyDirectory(realCandidate);
                throw new IllegalArgumentException("工作区目录越出了配置的项目根目录");
            }
            return realCandidate;
        } catch (FileAlreadyExistsException exception) {
            throw new ProjectWorkspaceConflictException("同名目录已存在");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建工作区目录", exception);
        }
    }

    private Path resolveBrowsePath(String value) throws IOException {
        if (selectionMode == SelectionMode.ROOT) {
            Path root = realRoot();
            String relative = value == null ? "" : value.trim();
            Path relativePath = Path.of(relative.isEmpty() ? "." : relative);
            boolean hasTraversal = StreamSupport.stream(relativePath.spliterator(), false)
                    .anyMatch(segment -> "..".equals(segment.toString()));
            if (relativePath.isAbsolute() || relative.indexOf('\\') >= 0 || hasTraversal) {
                throw new IllegalArgumentException("只能浏览配置的工作区根目录");
            }
            Path candidate = relative.isEmpty() ? root : root.resolve(relativePath).normalize();
            Path real = requireRealDirectory(candidate);
            if (!real.startsWith(root)) throw new IllegalArgumentException("目录越出了配置的工作区根目录");
            return real;
        }
        String requested = value == null ? "" : value.trim();
        Path start = requested.isEmpty() ? Path.of(System.getProperty("user.home")) : Path.of(requested);
        if (!start.isAbsolute()) throw new IllegalArgumentException("本地目录必须使用绝对路径");
        return requireRealDirectory(start);
    }

    private ResolvedSelection resolveSelection(String path) {
        try {
            Path real = resolveBrowsePath(path);
            if (!isSelectable(real)) throw new IllegalArgumentException("请选择一个文件夹");
            if (selectionMode == SelectionMode.ROOT) {
                Path root = realRoot();
                String relative = root.relativize(real).toString().replace('\\', '/');
                if (relative.isEmpty()) throw new IllegalArgumentException("不能将工作区根目录本身登记为项目");
                return new ResolvedSelection(PathScope.ROOT, relative, real);
            }
            return new ResolvedSelection(PathScope.LOCAL, real.toString(), real);
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法选择该文件夹", exception);
        }
    }

    private Path resolveStoredProjectPath(String scope, String storedPath) {
        try {
            if (PathScope.ROOT.name().equals(scope)) {
                Path root = realRoot();
                Path relative = Path.of(storedPath == null ? "" : storedPath);
                boolean hasTraversal = StreamSupport.stream(relative.spliterator(), false)
                        .anyMatch(segment -> "..".equals(segment.toString()));
                if (relative.isAbsolute() || hasTraversal) throw new IllegalArgumentException("项目路径记录无效");
                Path real = requireRealDirectory(root.resolve(relative).normalize());
                if (!real.startsWith(root)) throw new IllegalArgumentException("项目目录越出了配置根目录");
                return real;
            }
            if (!PathScope.LOCAL.name().equals(scope) || selectionMode != SelectionMode.LOCAL) {
                throw new IllegalArgumentException("当前部署模式不能访问该本地项目");
            }
            Path local = Path.of(storedPath == null ? "" : storedPath);
            if (!local.isAbsolute()) throw new IllegalArgumentException("本地项目路径记录无效");
            return requireRealDirectory(local);
        } catch (IOException exception) {
            throw new IllegalArgumentException("项目目录不存在或无法访问", exception);
        }
    }

    private Path requireRealDirectory(Path candidate) throws IOException {
        if (Files.isSymbolicLink(candidate)) throw new IllegalArgumentException("不能选择符号链接目录");
        Path real = candidate.toRealPath();
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("请选择一个存在的文件夹");
        return real;
    }

    private Path realRoot() throws IOException {
        Files.createDirectories(configuredRoot);
        return configuredRoot.toRealPath();
    }

    private Path allowedParent(Path current) throws IOException {
        Path parent = current.getParent();
        if (selectionMode == SelectionMode.LOCAL) return parent;
        Path root = realRoot();
        return current.equals(root) ? null : parent;
    }

    private boolean isSelectable(Path current) throws IOException {
        return selectionMode == SelectionMode.LOCAL || !current.equals(realRoot());
    }

    private String toClientPath(Path path) {
        if (selectionMode == SelectionMode.LOCAL) return path.toString();
        try {
            String relative = realRoot().relativize(path).toString().replace('\\', '/');
            return relative;
        } catch (IOException exception) {
            throw new IllegalStateException("无法解析工作区路径", exception);
        }
    }

    private String toDisplayPath(Path path) {
        if (selectionMode == SelectionMode.LOCAL) return path.toString();
        String relative = toClientPath(path);
        return relative.isEmpty() ? "/" : "/" + relative;
    }

    private String requireDisplayName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH || containsControlCharacter(normalized)) {
            throw new IllegalArgumentException("工作区名称长度必须为 1-80 个字符");
        }
        return normalized;
    }

    private String requireDirectoryName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_DIRECTORY_LENGTH
                || ".".equals(normalized) || "..".equals(normalized)
                || normalized.indexOf('/') >= 0 || normalized.indexOf('\\') >= 0
                || containsControlCharacter(normalized) || Path.of(normalized).isAbsolute()) {
            throw new IllegalArgumentException("文件夹名称必须是 1-128 个字符的单级目录名");
        }
        return normalized;
    }

    private boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private void deleteNewEmptyDirectory(Path directory) {
        try {
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // The directory was created by this request; leave it intact if it is no longer empty.
        }
    }

    private enum SelectionMode {
        LOCAL, ROOT;

        private static SelectionMode parse(String value) {
            try {
                return valueOf(value == null ? "LOCAL" : value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("app.project-workspace.selection-mode 只能是 local 或 root");
            }
        }
    }

    private enum PathScope { LOCAL, ROOT }

    private record ResolvedSelection(PathScope scope, String storedPath, Path realPath) {
    }
}
