package com.zwx.zwxagent.capability.tools;

import com.zwx.zwxagent.platform.workspace.ProjectExecutionLockService;
import com.zwx.zwxagent.platform.workspace.ProjectPathGuard;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 经确认后在 DIRECT 项目目录内应用 unified diff。 */
public class ProjectCodeWriteTool {

    private static final int MAX_PATCH_CHARS = 200_000;
    private static final int MAX_OUTPUT_CHARS = 80_000;
    private final String projectId;
    private final String runId;
    private final ProjectPathGuard guard;
    private final ProjectExecutionLockService lockService;
    private final ProjectCodeReadTool readTool;

    public ProjectCodeWriteTool(String projectId, String projectName, Path projectRoot, String runId,
                                ProjectExecutionLockService lockService) {
        this.projectId = projectId;
        this.runId = runId;
        this.guard = new ProjectPathGuard(projectRoot);
        this.lockService = lockService;
        this.readTool = new ProjectCodeReadTool(projectName, projectRoot);
    }

    @Tool(description = "Apply a unified Git patch to the current project after validating every project-relative path. The operation modifies source files and requires user confirmation.")
    public String applyProjectPatch(@ToolParam(description = "Unified diff using a/ and b/ project-relative paths") String patch) {
        String validation = validatePatch(patch);
        if (validation != null) return "Error applying project patch: " + validation;
        if (!Files.exists(guard.root().resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            return "Error applying project patch: the current project is not a Git repository";
        }
        lockService.acquire(projectId, runId);
        CommandResult check = gitApply(patch, true);
        if (!check.success()) {
            lockService.release(runId);
            return "Error applying project patch: patch check failed\n" + check.output();
        }
        CommandResult apply = gitApply(patch, false);
        if (!apply.success()) {
            lockService.release(runId);
            return "Error applying project patch: " + apply.output();
        }
        return "Patch applied successfully. Current project diff:\n" + readTool.getProjectDiff();
    }

    private String validatePatch(String patch) {
        if (patch == null || patch.isBlank()) return "patch is required";
        if (patch.length() > MAX_PATCH_CHARS) return "patch exceeds the 200,000 character limit";
        int paths = 0;
        for (String line : patch.split("\\R")) {
            String value = null;
            if (line.startsWith("+++ ") || line.startsWith("--- ")) value = line.substring(4).trim();
            if (value == null || value.equals("/dev/null")) continue;
            int tab = value.indexOf('\t');
            if (tab >= 0) value = value.substring(0, tab);
            if (value.startsWith("a/") || value.startsWith("b/")) value = value.substring(2);
            try {
                Path target = guard.resolveForWrite(value);
                if (guard.isSensitive(target)) return "sensitive path is not writable: " + value;
                paths++;
            } catch (IllegalArgumentException exception) {
                return exception.getMessage();
            }
        }
        return paths == 0 ? "patch does not contain project file paths" : null;
    }

    private CommandResult gitApply(String patch, boolean checkOnly) {
        List<String> command = checkOnly
                ? List.of("git", "apply", "--check", "--recount", "-")
                : List.of("git", "apply", "--recount", "--whitespace=nowarn", "-");
        ProcessBuilder builder = new ProcessBuilder(command).directory(guard.root().toFile()).redirectErrorStream(true);
        StringBuilder output = new StringBuilder();
        try {
            Process process = builder.start();
            process.getOutputStream().write(patch.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && output.length() < MAX_OUTPUT_CHARS) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new CommandResult(false, "git apply timed out");
            }
            return new CommandResult(process.exitValue() == 0, output.toString());
        } catch (Exception exception) {
            return new CommandResult(false, exception.getMessage());
        }
    }

    private record CommandResult(boolean success, String output) {
    }
}
