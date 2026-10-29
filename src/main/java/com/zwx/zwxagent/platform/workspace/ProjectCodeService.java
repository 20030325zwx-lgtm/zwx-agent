package com.zwx.zwxagent.platform.workspace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 会话级代码工作区：读取文件树 / 查看文件 / 编辑保存 / 查看改动。
 *
 * 目标目录优先使用 run 对应的 checkout（Git 项目为 worktree，能看到智能体本次改动），
 * 没有 run 时回退到绑定项目的根目录。所有路径都经过 ProjectPathGuard 与敏感文件校验。
 */
@Service
public class ProjectCodeService {

    private static final long MAX_FILE_BYTES = 1_000_000;
    private static final int MAX_TREE_ENTRIES = 800;
    private static final int MAX_PATCH_CHARS = 400_000;
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", "build", "dist", ".gradle", ".idea", ".vscode",
            ".next", ".nuxt", ".cache", "coverage", "out", "vendor", "pgserver", "release",
            "runtime", "temp", ".DS_Store");

    private final ProjectWorkspaceService projectWorkspaceService;
    private final RunWorkspaceService runWorkspaceService;
    private final JdbcTemplate jdbcTemplate;

    public ProjectCodeService(ProjectWorkspaceService projectWorkspaceService,
                              RunWorkspaceService runWorkspaceService,
                              JdbcTemplate jdbcTemplate) {
        this.projectWorkspaceService = projectWorkspaceService;
        this.runWorkspaceService = runWorkspaceService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Entry(String name, String path, String type, long size) {
    }

    public record TreeView(String path, List<Entry> entries) {
    }

    public record FileView(String path, String content, long size, long modifiedAt, boolean editable) {
    }

    public record DiffFile(String path, String status, int additions, int deletions) {
    }

    public record DiffView(String mode, String branch, String baseHead, List<DiffFile> files, String patch) {
    }

    public TreeView tree(String tenantId, String userId, String conversationId, String runId, String path) {
        Target target = resolveTarget(tenantId, userId, conversationId, runId);
        Path directory = target.guard().resolveExisting(path);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标不是目录");
        }
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(child -> !Files.isSymbolicLink(child))
                    .filter(child -> !isSkipped(child, target.guard()))
                    .sorted(Comparator
                            .comparing((Path child) -> !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
                            .thenComparing(child -> child.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .limit(MAX_TREE_ENTRIES)
                    .forEach(child -> {
                        boolean isDirectory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                        long size = 0;
                        if (!isDirectory) {
                            try {
                                size = Files.size(child);
                            } catch (Exception ignored) {
                            }
                        }
                        entries.add(new Entry(child.getFileName().toString(),
                                target.guard().relative(child), isDirectory ? "directory" : "file", size));
                    });
        } catch (Exception exception) {
            throw new IllegalArgumentException("目录读取失败：" + exception.getMessage(), exception);
        }
        String relative = path == null ? "" : path.trim().replace('\\', '/');
        return new TreeView(relative.equals(".") ? "" : relative, entries);
    }

    public FileView readFile(String tenantId, String userId, String conversationId, String runId, String path) {
        Target target = resolveTarget(tenantId, userId, conversationId, runId);
        Path file = target.guard().resolveExisting(path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标不是文件");
        }
        if (target.guard().isSensitive(file)) {
            throw new IllegalArgumentException("该文件属于敏感路径，不允许在线查看");
        }
        long size;
        try {
            size = Files.size(file);
        } catch (Exception exception) {
            throw new IllegalArgumentException("文件无法访问", exception);
        }
        if (size > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件过大（超过 1MB），请下载后查看");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (Exception exception) {
            throw new IllegalArgumentException("文件读取失败", exception);
        }
        for (int index = 0; index < Math.min(bytes.length, 8000); index++) {
            if (bytes[index] == 0) throw new IllegalArgumentException("二进制文件不支持在线查看");
        }
        long modifiedAt = 0;
        try {
            FileTime time = Files.getLastModifiedTime(file);
            modifiedAt = time.toMillis();
        } catch (Exception ignored) {
        }
        return new FileView(target.guard().relative(file), new String(bytes, StandardCharsets.UTF_8),
                size, modifiedAt, true);
    }

    public FileView writeFile(String tenantId, String userId, String conversationId, String runId,
                              String path, String content, Long expectedModifiedAt) {
        Target target = resolveTarget(tenantId, userId, conversationId, runId);
        Path file = target.guard().resolveForWrite(path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("只能编辑已存在的文件");
        }
        if (target.guard().isSensitive(file)) {
            throw new IllegalArgumentException("该文件属于敏感路径，不允许在线编辑");
        }
        String text = content == null ? "" : content;
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("内容超过 1MB 上限");
        }
        if (expectedModifiedAt != null && expectedModifiedAt > 0) {
            try {
                long current = Files.getLastModifiedTime(file).toMillis();
                if (current != expectedModifiedAt) {
                    throw new ProjectFileConflictException("文件已被修改，请刷新后再编辑");
                }
            } catch (ProjectFileConflictException conflict) {
                throw conflict;
            } catch (Exception ignored) {
            }
        }
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalArgumentException("文件保存失败", exception);
        }
        return readFile(tenantId, userId, conversationId, runId, path);
    }

    public DiffView diff(String tenantId, String userId, String conversationId, String runId) {
        Target target = resolveTarget(tenantId, userId, conversationId, runId);
        Path root = target.root();
        if (!gitSuccess(root, List.of("rev-parse", "--is-inside-work-tree"))) {
            return new DiffView(target.runContext() == null ? "PROJECT" : "RUN", null, null, List.of(), "");
        }
        String mode = target.runContext() == null ? "PROJECT" : "RUN";
        String branch = target.runContext() == null ? null : target.runContext().branchName();
        String base = target.runContext() == null
                ? (gitSuccess(root, List.of("rev-parse", "--verify", "HEAD")) ? "HEAD" : null)
                : target.runContext().baseHead();
        String baseHead = target.runContext() == null ? null : target.runContext().baseHead();

        Map<String, DiffFile> files = new LinkedHashMap<>();
        String status = git(root, List.of("status", "--porcelain=v1", "-z"), 15);
        for (String entry : status.split("\u0000")) {
            if (entry.isBlank()) continue;
            String code = entry.length() >= 2 ? entry.substring(0, 2).trim() : "?";
            String filePath = entry.length() > 3 ? entry.substring(3) : "";
            int arrow = filePath.indexOf(" -> ");
            if (arrow >= 0) filePath = filePath.substring(arrow + 4);
            if (filePath.isBlank() || filePath.endsWith("/")) continue;
            files.put(filePath, new DiffFile(filePath, normalizeStatus(code), 0, 0));
        }

        List<String> numstatArgs = new ArrayList<>(List.of("diff", "--numstat"));
        if (base != null) numstatArgs.add(base);
        for (String line : git(root, numstatArgs, 20).split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length < 3) continue;
            String filePath = parts[2];
            int additions = parseCount(parts[0]);
            int deletions = parseCount(parts[1]);
            DiffFile existing = files.get(filePath);
            files.put(filePath, new DiffFile(filePath,
                    existing == null ? "modified" : existing.status(), additions, deletions));
        }

        List<String> diffArgs = new ArrayList<>(List.of("diff", "--no-color", "--unified=3"));
        if (base != null) diffArgs.add(base);
        String patch = git(root, diffArgs, 30);
        if (patch.length() > MAX_PATCH_CHARS) {
            patch = patch.substring(0, MAX_PATCH_CHARS) + "\n...（差异过大，已截断）";
        }
        return new DiffView(mode, branch, baseHead, new ArrayList<>(files.values()), patch);
    }

    /**
     * 撤销某个文件的部分/全部改动：hunkIndex < 0 表示整文件。
     * 只接受当前 diff 中真实存在的 hunk，避免任意 patch 注入。
     */
    public DiffView revertHunk(String tenantId, String userId, String conversationId, String runId,
                               String path, int hunkIndex) {
        requireRunFinished(runId);
        Target target = resolveTarget(tenantId, userId, conversationId, runId);
        Path file = target.guard().resolveExisting(path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标不是文件");
        }
        String relative = target.guard().relative(file);
        String base = diffBase(target);
        if (base == null) throw new IllegalArgumentException("当前项目没有可撤销的版本基线");
        String filePatch = git(target.root(), List.of("diff", "--no-color", "--unified=3", base, "--", relative), 30);
        if (filePatch.isBlank()) {
            throw new IllegalArgumentException("该文件没有可撤销的改动；未跟踪文件请使用删除操作");
        }
        List<String> lines = List.of(filePatch.split("\n", -1));
        int firstHunk = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith("@@")) {
                firstHunk = index;
                break;
            }
        }
        if (firstHunk < 0) throw new IllegalArgumentException("该文件没有可撤销的改动");
        List<List<String>> hunks = new ArrayList<>();
        List<String> header = new ArrayList<>(lines.subList(0, firstHunk));
        List<String> current = null;
        for (int index = firstHunk; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith("@@")) {
                current = new ArrayList<>();
                hunks.add(current);
            }
            if (current != null) current.add(line);
        }
        if (hunkIndex >= hunks.size()) {
            throw new IllegalArgumentException("改动片段不存在，请刷新后重试");
        }
        StringBuilder patch = new StringBuilder();
        header.forEach(line -> patch.append(line).append('\n'));
        if (hunkIndex < 0) {
            hunks.forEach(hunk -> hunk.forEach(line -> patch.append(line).append('\n')));
        } else {
            hunks.get(hunkIndex).forEach(line -> patch.append(line).append('\n'));
        }
        CommandResult applied = gitApplyReverse(target.root(), patch.toString());
        if (!applied.success()) {
            throw new IllegalArgumentException("撤销失败：" + applied.output().trim());
        }
        return diff(tenantId, userId, conversationId, runId);
    }

    /** 删除未跟踪文件（新增文件的「撤销」）。 */
    public DiffView deleteUntrackedFile(String tenantId, String userId, String conversationId, String runId,
                                        String path) {
        requireRunFinished(runId);
        Target target = resolveTarget(tenantId, userId, conversationId, runId);
        Path file = target.guard().resolveExisting(path);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("目标不是文件");
        }
        String relative = target.guard().relative(file);
        String status = git(target.root(), List.of("status", "--porcelain=v1", "--", relative), 15);
        if (!status.trim().startsWith("??")) {
            throw new IllegalArgumentException("只能删除未跟踪文件；已跟踪文件请撤销改动");
        }
        try {
            Files.delete(file);
        } catch (Exception exception) {
            throw new IllegalArgumentException("文件删除失败：" + exception.getMessage(), exception);
        }
        return diff(tenantId, userId, conversationId, runId);
    }

    /** 运行仍在进行时禁止撤销，避免与智能体同时改写同一 checkout。 */
    private void requireRunFinished(String runId) {
        if (runId == null || runId.isBlank()) return;
        String status = jdbcTemplate.query(
                "SELECT status FROM agent_run WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                runId);
        if ("RUNNING".equals(status)) {
            throw new IllegalArgumentException("运行仍在进行，请等待结束后再撤销改动");
        }
    }

    private String diffBase(Target target) {
        if (target.runContext() != null) return target.runContext().baseHead();
        return gitSuccess(target.root(), List.of("rev-parse", "--verify", "HEAD")) ? "HEAD" : null;
    }

    private CommandResult gitApplyReverse(Path root, String patch) {
        ProcessBuilder builder = new ProcessBuilder("git", "-c", "core.hooksPath=/dev/null",
                "-C", root.toString(), "apply", "-R", "--whitespace=nowarn", "-");
        builder.redirectErrorStream(true);
        StringBuilder output = new StringBuilder();
        try {
            Process process = builder.start();
            try (var writer = process.getOutputStream()) {
                writer.write(patch.getBytes(StandardCharsets.UTF_8));
            }
            Thread reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader lines = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) output.append(line).append('\n');
                } catch (Exception ignored) {
                }
            });
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            reader.join(2000);
            return new CommandResult(completed && process.exitValue() == 0, output.toString());
        } catch (Exception exception) {
            return new CommandResult(false, exception.getMessage());
        }
    }

    private Target resolveTarget(String tenantId, String userId, String conversationId, String runId) {
        ResolvedProjectWorkspace project = projectWorkspaceService
                .resolveForConversation(tenantId, userId, conversationId)
                .orElseThrow(() -> new IllegalArgumentException("当前会话未绑定工作区"));
        if (runId != null && !runId.isBlank()) {
            Optional<RunWorkspaceContext> run = runWorkspaceService.find(tenantId, userId, conversationId, runId);
            if (run.isPresent()) {
                return new Target(project, run.get().checkoutRoot(), run.get(),
                        new ProjectPathGuard(run.get().checkoutRoot()));
            }
        }
        return new Target(project, project.root(), null, new ProjectPathGuard(project.root()));
    }

    private record Target(ResolvedProjectWorkspace project, Path root,
                          RunWorkspaceContext runContext, ProjectPathGuard guard) {
    }

    private boolean isSkipped(Path child, ProjectPathGuard guard) {
        String name = child.getFileName().toString();
        if (SKIPPED_DIRECTORIES.contains(name)) return true;
        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) && guard.isSensitive(child)) return true;
        return false;
    }

    private String normalizeStatus(String code) {
        if (code == null || code.isEmpty()) return "modified";
        char first = code.charAt(0);
        char last = code.charAt(code.length() - 1);
        if (code.equals("??")) return "untracked";
        if (first == 'A' || last == 'A') return "added";
        if (first == 'D' || last == 'D') return "deleted";
        if (last == 'R') return "renamed";
        return "modified";
    }

    private int parseCount(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private boolean gitSuccess(Path root, List<String> arguments) {
        return gitResult(root, arguments, 15).success();
    }

    private String git(Path root, List<String> arguments, int timeoutSeconds) {
        CommandResult result = gitResult(root, arguments, timeoutSeconds);
        return result.success() ? result.output() : "";
    }

    private CommandResult gitResult(Path root, List<String> arguments, int timeoutSeconds) {
        List<String> command = new ArrayList<>();
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
                try (BufferedReader lines = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null && output.length() < MAX_PATCH_CHARS + 20_000) {
                        output.append(line).append('\n');
                    }
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

    private record CommandResult(boolean success, String output) {
    }

    /** 编辑并发冲突：Controller 映射为 409。 */
    public static class ProjectFileConflictException extends RuntimeException {
        public ProjectFileConflictException(String message) {
            super(message);
        }
    }
}
