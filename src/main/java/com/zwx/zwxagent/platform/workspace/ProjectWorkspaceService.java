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
import java.util.List;
import java.util.UUID;

@Service
public class ProjectWorkspaceService {

    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_DIRECTORY_LENGTH = 128;

    private final JdbcTemplate jdbcTemplate;
    private final Path configuredRoot;

    public ProjectWorkspaceService(JdbcTemplate jdbcTemplate,
                                   @Value("${app.project-workspace.root:${user.dir}/projects}") String root) {
        this.jdbcTemplate = jdbcTemplate;
        this.configuredRoot = Path.of(root).toAbsolutePath().normalize();
    }

    public List<ProjectWorkspace> list(String tenantId, String userId) {
        return jdbcTemplate.query("""
                SELECT id, name, directory_name, created_at, updated_at
                FROM agent_project
                WHERE tenant_id = ? AND owner_user_id = ?
                ORDER BY updated_at DESC, name ASC
                """, (rs, rowNum) -> new ProjectWorkspace(
                rs.getString("id"), rs.getString("name"), rs.getString("directory_name"),
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
                    INSERT INTO agent_project (id, tenant_id, owner_user_id, name, directory_name)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, tenantId, userId, safeName, safeDirectoryName);
            return jdbcTemplate.queryForObject("""
                    SELECT id, name, directory_name, created_at, updated_at
                    FROM agent_project WHERE id = ? AND tenant_id = ? AND owner_user_id = ?
                    """, (rs, rowNum) -> new ProjectWorkspace(
                    rs.getString("id"), rs.getString("name"), rs.getString("directory_name"),
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

    public boolean isOwned(String tenantId, String userId, String projectId) {
        if (projectId == null || projectId.isBlank()) return false;
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_project
                WHERE id = ? AND tenant_id = ? AND owner_user_id = ?
                """, Integer.class, projectId, tenantId, userId);
        return count != null && count > 0;
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
}
