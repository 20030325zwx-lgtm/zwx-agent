package com.zwx.zwxagent.capability.tools;

import com.zwx.zwxagent.platform.workspace.ProjectExecutionLockService;
import com.zwx.zwxagent.platform.workspace.ProjectPathGuard;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 在当前受管 checkout 内暂存并提交变更；不提供 fetch/push/reset。 */
public class ProjectGitCommitTool {

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private final String projectId;
    private final String runId;
    private final ProjectPathGuard guard;
    private final ProjectExecutionLockService lockService;
    private final boolean directMode;

    public ProjectGitCommitTool(String projectId, Path checkoutRoot, String runId,
                                ProjectExecutionLockService lockService, boolean directMode) {
        this.projectId = projectId;
        this.runId = runId;
        this.guard = new ProjectPathGuard(checkoutRoot);
        this.lockService = lockService;
        this.directMode = directMode;
    }

    @Tool(description = "Stage all non-sensitive changes in the current managed checkout and create a local Git commit. Hooks, signing and push are disabled; user confirmation is required.")
    public String commitProjectChanges(@ToolParam(description = "Commit message, 1-2000 characters") String message) {
        String safeMessage = message == null ? "" : message.trim();
        if (safeMessage.isEmpty() || safeMessage.length() > 2000 || safeMessage.codePoints().anyMatch(Character::isISOControl)) {
            return "Error: commit message must contain 1-2000 printable characters";
        }
        if (!Files.exists(guard.root().resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            return "Error: current checkout is not a Git repository";
        }
        String validation = validateChangedFiles();
        if (validation != null) return "Error: " + validation;
        if (directMode) lockService.acquire(projectId, runId);

        CommandResult add = git(List.of("add", "-A", "--", "."), 30);
        if (add.exitCode() != 0) return "Error staging project changes:\n" + add.output();
        CommandResult staged = git(List.of("diff", "--cached", "--quiet"), 15);
        if (staged.exitCode() == 0) return "No project changes to commit";
        if (staged.exitCode() != 1) return "Error checking staged changes:\n" + staged.output();

        CommandResult commit = git(List.of("-c", "user.name=Aster Agent", "-c",
                "user.email=aster-agent@localhost", "-c", "commit.gpgSign=false",
                "commit", "--no-gpg-sign", "-m", safeMessage), 60);
        if (commit.exitCode() != 0) return "Error creating project commit:\n" + commit.output();
        CommandResult head = git(List.of("rev-parse", "HEAD"), 15);
        CommandResult status = git(List.of("status", "--short", "--branch"), 15);
        return "Commit created: " + head.output().trim() + "\n" + commit.output() + status.output();
    }

    private String validateChangedFiles() {
        CommandResult status = git(List.of("status", "--porcelain=v1", "-z", "--untracked-files=all"), 15);
        if (status.exitCode() != 0) return "unable to inspect Git status: " + status.output();
        if (status.raw().length == 0) return null;
        String[] records = new String(status.raw(), StandardCharsets.UTF_8).split("\u0000");
        for (String record : records) {
            if (record.isBlank()) continue;
            String relative = record.length() > 3 && record.charAt(2) == ' ' ? record.substring(3) : record;
            try {
                Path file = guard.resolveForWrite(relative);
                if (guard.isSensitive(file)) return "sensitive file cannot be committed: " + relative;
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) > MAX_FILE_BYTES) {
                    return "file exceeds the 10 MB commit limit: " + relative;
                }
            } catch (Exception exception) {
                return "invalid changed path " + relative + ": " + exception.getMessage();
            }
        }
        return null;
    }

    private CommandResult git(List<String> arguments, int timeoutSeconds) {
        ArrayList<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("core.hooksPath=/dev/null");
        command.add("-c");
        command.add("core.quotepath=false");
        command.add("-C");
        command.add(guard.root().toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_PAGER", "cat");
        builder.environment().put("PAGER", "cat");
        try {
            Process process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (InputStream input = process.getInputStream()) {
                    input.transferTo(output);
                } catch (Exception ignored) {
                }
            });
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            reader.join(2000);
            byte[] raw = output.toByteArray();
            String text = new String(raw, 0, Math.min(raw.length, 80_000), StandardCharsets.UTF_8);
            if (!completed) return new CommandResult(-1, text + "\n...[timed out]", raw);
            return new CommandResult(process.exitValue(), text, raw);
        } catch (Exception exception) {
            return new CommandResult(-1, exception.getMessage(), new byte[0]);
        }
    }

    private record CommandResult(int exitCode, String output, byte[] raw) {
    }
}
