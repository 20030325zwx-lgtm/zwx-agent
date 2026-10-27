package com.zwx.zwxagent.platform.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** 创建、持久化并恢复 Run 对应的 DIRECT/WORKTREE 源码 checkout。 */
@Service
public class RunWorkspaceService {

    private final JdbcTemplate jdbcTemplate;
    private final ProjectWorkspaceService projectWorkspaceService;
    private final Path worktreeRoot;

    public RunWorkspaceService(JdbcTemplate jdbcTemplate, ProjectWorkspaceService projectWorkspaceService,
                               @Value("${app.project-workspace.worktree-root:${java.io.tmpdir}/aster-worktrees}") String worktreeRoot) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectWorkspaceService = projectWorkspaceService;
        this.worktreeRoot = Path.of(worktreeRoot).toAbsolutePath().normalize();
    }

    public Optional<RunWorkspaceContext> resolve(String tenantId, String userId, String conversationId, String runId) {
        Optional<ResolvedProjectWorkspace> project = projectWorkspaceService.resolveForConversation(
                tenantId, userId, conversationId);
        if (project.isEmpty()) return Optional.empty();
        Optional<RunWorkspaceContext> existing = readExisting(runId, project.get());
        if (existing.isPresent()) return existing;
        return Optional.of(create(project.get(), runId));
    }

    public void finish(String runId, String status) {
        if (runId == null || runId.isBlank()) return;
        String normalized = switch (status == null ? "FAILED" : status) {
            case "COMPLETED" -> "COMPLETED";
            case "INTERRUPTED", "CANCELLED" -> "INTERRUPTED";
            default -> "FAILED";
        };
        jdbcTemplate.update("UPDATE agent_run_workspace SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE run_id = ?",
                normalized, runId);
    }

    private Optional<RunWorkspaceContext> readExisting(String runId, ResolvedProjectWorkspace project) {
        return jdbcTemplate.query("""
                SELECT mode, checkout_path, branch_name, base_head
                FROM agent_run_workspace WHERE run_id = ? AND project_id = ?
                """, (rs, rowNum) -> {
            Path checkout = Path.of(rs.getString("checkout_path")).toAbsolutePath().normalize();
            try {
                checkout = checkout.toRealPath();
            } catch (Exception exception) {
                throw new IllegalStateException("Run 对应的项目 checkout 已丢失", exception);
            }
            return new RunWorkspaceContext(runId, project.projectId(), project.name(), checkout,
                    RunWorkspaceContext.Mode.valueOf(rs.getString("mode")), rs.getString("branch_name"),
                    rs.getString("base_head"));
        }, runId, project.projectId()).stream().findFirst();
    }

    private RunWorkspaceContext create(ResolvedProjectWorkspace project, String runId) {
        boolean git = Files.exists(project.root().resolve(".git"), LinkOption.NOFOLLOW_LINKS);
        if (!git) {
            insert(runId, project.projectId(), RunWorkspaceContext.Mode.DIRECT, project.root(), null, null);
            return new RunWorkspaceContext(runId, project.projectId(), project.name(), project.root(),
                    RunWorkspaceContext.Mode.DIRECT, null, null);
        }
        String safeProject = sanitize(project.projectId());
        String safeRun = sanitize(runId);
        Path checkout = worktreeRoot.resolve(safeProject).resolve(safeRun).normalize();
        if (!checkout.startsWith(worktreeRoot)) throw new IllegalArgumentException("无效的 worktree 路径");
        String branch = "aster/run-" + safeRun;
        String baseHead = git(project.root(), List.of("rev-parse", "HEAD"), 15).trim();
        try {
            Files.createDirectories(checkout.getParent());
        } catch (Exception exception) {
            throw new IllegalStateException("无法创建 worktree 管理目录", exception);
        }
        CommandResult result = gitResult(project.root(),
                List.of("worktree", "add", "-b", branch, checkout.toString(), baseHead), 60);
        if (!result.success()) throw new IllegalStateException("无法创建 Git worktree：" + result.output());
        try {
            Path realCheckout = checkout.toRealPath();
            insert(runId, project.projectId(), RunWorkspaceContext.Mode.WORKTREE, realCheckout, branch, baseHead);
            return new RunWorkspaceContext(runId, project.projectId(), project.name(), realCheckout,
                    RunWorkspaceContext.Mode.WORKTREE, branch, baseHead);
        } catch (RuntimeException exception) {
            gitResult(project.root(), List.of("worktree", "remove", "--force", checkout.toString()), 30);
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法解析新建的 worktree", exception);
        }
    }

    private void insert(String runId, String projectId, RunWorkspaceContext.Mode mode, Path checkout,
                        String branch, String baseHead) {
        jdbcTemplate.update("""
                INSERT INTO agent_run_workspace
                    (run_id, project_id, mode, checkout_path, branch_name, base_head)
                VALUES (?, ?, ?, ?, ?, ?)
                """, runId, projectId, mode.name(), checkout.toString(), branch, baseHead);
    }

    private String git(Path root, List<String> arguments, int timeoutSeconds) {
        CommandResult result = gitResult(root, arguments, timeoutSeconds);
        if (!result.success()) throw new IllegalStateException(result.output());
        return result.output();
    }

    private CommandResult gitResult(Path root, List<String> arguments, int timeoutSeconds) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("core.hooksPath=/dev/null");
        command.add("-C");
        command.add(root.toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        StringBuilder output = new StringBuilder();
        try {
            Process process = builder.start();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null && output.length() < 40_000) output.append(line).append('\n');
                } catch (Exception ignored) {
                }
            });
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            reader.join(2000);
            return new CommandResult(completed && process.exitValue() == 0,
                    completed ? output.toString() : output + "\n...[timed out]");
        } catch (Exception exception) {
            return new CommandResult(false, exception.getMessage());
        }
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("run/project id 不能为空");
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private record CommandResult(boolean success, String output) {
    }
}
