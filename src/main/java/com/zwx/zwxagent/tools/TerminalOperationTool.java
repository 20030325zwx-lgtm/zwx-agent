package com.zwx.zwxagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class TerminalOperationTool {

    private static final long TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_CHARS = 8_000;

    private final Path workDir;
    private final Set<String> allowedCommands;

    public TerminalOperationTool(Path workDir, Set<String> allowedCommands) {
        this.workDir = workDir;
        this.allowedCommands = allowedCommands;
    }

    @Tool(description = "Execute a read-only whitelisted command inside the current task workspace")
    public String executeTerminalCommand(@ToolParam(description = "Command to execute; the first token must be a whitelisted command and only workspace-relative paths are allowed") String command) {
        // 第二道防线：与 ToolGuardHook 共用同一中央策略（allowlist + shell 元字符 + 越权路径）
        String violation = ToolGuardPolicy.terminalViolation(command, allowedCommands);
        if (violation != null) {
            return "Error executing command: " + violation;
        }
        String trimmed = command.trim();
        StringBuilder output = new StringBuilder();
        try {
            boolean windows = File.separatorChar == '\\';
            ProcessBuilder builder = windows
                    ? new ProcessBuilder("cmd.exe", "/c", trimmed)
                    : new ProcessBuilder("sh", "-c", trimmed);
            builder.directory(workDir.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > MAX_OUTPUT_CHARS) {
                        process.destroyForcibly();
                        return output.append("\n...[output truncated]").toString();
                    }
                    output.append(line).append("\n");
                }
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "Error executing command: timed out after " + TIMEOUT_SECONDS + " seconds";
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                output.append("Command execution failed with exit code: ").append(exitCode);
            }
        } catch (IOException exception) {
            return "Error executing command: " + exception.getMessage();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "Error executing command: interrupted";
        }
        return output.length() > MAX_OUTPUT_CHARS
                ? output.substring(0, MAX_OUTPUT_CHARS) + "\n...[output truncated]"
                : output.toString();
    }
}
