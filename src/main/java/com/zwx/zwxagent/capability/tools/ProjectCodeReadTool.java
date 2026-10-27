package com.zwx.zwxagent.capability.tools;

import com.zwx.zwxagent.platform.workspace.ProjectPathGuard;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** 当前对话绑定项目的只读源码导航工具。 */
public class ProjectCodeReadTool {

    private static final int MAX_TREE_ENTRIES = 500;
    private static final int MAX_READ_CHARS = 100_000;
    private static final int MAX_COMMAND_CHARS = 40_000;
    private final String projectName;
    private final ProjectPathGuard guard;

    public ProjectCodeReadTool(String projectName, Path projectRoot) {
        this.projectName = projectName;
        this.guard = new ProjectPathGuard(projectRoot);
    }

    @Tool(description = "List the current project's directory tree from a project-relative path. Hidden secrets, .git internals, symlinks and generated dependency directories are excluded.")
    public String listProjectTree(@ToolParam(description = "Project-relative directory, or . for the project root") String path) {
        Path start;
        try {
            start = guard.resolveExisting(path);
        } catch (IllegalArgumentException exception) {
            return "Error listing project: " + exception.getMessage();
        }
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) return "Error: path is not a directory";
        List<Path> entries;
        try (Stream<Path> stream = Files.walk(start, 4)) {
            entries = stream.filter(candidate -> !candidate.equals(start))
                    .filter(candidate -> !Files.isSymbolicLink(candidate))
                    .filter(candidate -> !guard.isSensitive(candidate))
                    .filter(candidate -> !isGeneratedPath(candidate))
                    .sorted(Comparator.comparing(guard::relative))
                    .limit(MAX_TREE_ENTRIES + 1L)
                    .toList();
        } catch (IOException exception) {
            return "Error listing project: " + exception.getMessage();
        }
        boolean truncated = entries.size() > MAX_TREE_ENTRIES;
        StringBuilder result = new StringBuilder("Project: ").append(projectName).append('\n');
        entries.stream().limit(MAX_TREE_ENTRIES).forEach(entry -> result.append(Files.isDirectory(entry) ? "D " : "F ")
                .append(guard.relative(entry)).append('\n'));
        if (truncated) result.append("...[truncated at ").append(MAX_TREE_ENTRIES).append(" entries]\n");
        return result.toString();
    }

    @Tool(description = "Search text or regular expressions in source files of the current project. Returns project-relative paths and line numbers.")
    public String searchProjectCode(@ToolParam(description = "ripgrep regular expression to search for") String query) {
        if (query == null || query.isBlank() || query.length() > 500) return "Error: query must contain 1-500 characters";
        return run(List.of("rg", "--line-number", "--no-heading", "--color", "never", "--hidden",
                "--glob", "!.git/**", "--glob", "!.env*", "--glob", "!node_modules/**",
                "--glob", "!target/**", "--glob", "!dist/**", "--max-count", "100", "--", query, "."), 10);
    }

    @Tool(description = "Read a UTF-8 source file from the current project by project-relative path. Output is bounded and sensitive files are denied.")
    public String readProjectFile(@ToolParam(description = "Project-relative source file path") String fileName) {
        Path file;
        try {
            file = guard.resolveExisting(fileName);
        } catch (IllegalArgumentException exception) {
            return "Error reading project file: " + exception.getMessage();
        }
        if (guard.isSensitive(file)) return "Error reading project file: sensitive files are not accessible";
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return "Error reading project file: file not found";
        try {
            long fileSize = Files.size(file);
            byte[] bytes;
            try (InputStream input = Files.newInputStream(file)) {
                bytes = input.readNBytes(MAX_READ_CHARS);
            }
            int length = bytes.length;
            for (int index = 0; index < Math.min(length, 8192); index++) {
                if (bytes[index] == 0) return "Error reading project file: binary files are not supported";
            }
            String content = new String(bytes, 0, length, StandardCharsets.UTF_8);
            return fileSize > length ? content + "\n...[truncated]" : content;
        } catch (IOException exception) {
            return "Error reading project file: " + exception.getMessage();
        }
    }

    @Tool(description = "Show the current Git branch and concise working tree status for the bound project.")
    public String getProjectGitStatus() {
        if (!isGitProject()) return "Current project is not a Git repository";
        return run(List.of("git", "-c", "core.quotepath=false", "status", "--short", "--branch", "--untracked-files=all"), 10);
    }

    @Tool(description = "Show the current unstaged and staged Git diff for the bound project without invoking external diff tools.")
    public String getProjectDiff() {
        if (!isGitProject()) return "Current project is not a Git repository";
        String unstaged = run(List.of("git", "-c", "core.quotepath=false", "diff", "--no-ext-diff", "--unified=3"), 10);
        String staged = run(List.of("git", "-c", "core.quotepath=false", "diff", "--cached", "--no-ext-diff", "--unified=3"), 10);
        return "Unstaged changes:\n" + unstaged + "\nStaged changes:\n" + staged;
    }

    private boolean isGitProject() {
        return Files.exists(guard.root().resolve(".git"), LinkOption.NOFOLLOW_LINKS);
    }

    private boolean isGeneratedPath(Path path) {
        for (Path segment : guard.root().relativize(path)) {
            if (switch (segment.toString()) {
                case "node_modules", "target", "dist", "build", ".gradle", ".idea", ".vscode" -> true;
                default -> false;
            }) return true;
        }
        return false;
    }

    private String run(List<String> command, int timeoutSeconds) {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.directory(guard.root().toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("GIT_PAGER", "cat");
        builder.environment().put("PAGER", "cat");
        StringBuilder output = new StringBuilder();
        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() >= MAX_COMMAND_CHARS) {
                        process.destroyForcibly();
                        return output.append("\n...[output truncated]").toString();
                    }
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "Error: command timed out";
            }
            if (process.exitValue() > 1) return "Error: command failed\n" + output;
            return output.isEmpty() ? "No results" : output.toString();
        } catch (IOException exception) {
            return "Error: required command is unavailable: " + command.getFirst();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "Error: command interrupted";
        }
    }
}
