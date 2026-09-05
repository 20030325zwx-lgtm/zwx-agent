package com.zwx.zwxagent.tools;

import cn.hutool.core.io.FileUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileOperationTool {

    private static final long MAX_WRITE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_READ_CHARS = 100_000;

    private final ToolSandbox sandbox;
    private final Path workDir;
    private final Path fileDir;

    public FileOperationTool(ToolSandbox sandbox, Path workDir) {
        this.sandbox = sandbox;
        this.workDir = workDir;
        this.fileDir = workDir.resolve("file");
    }

    @Tool(description = "Read content from a file inside the current task workspace")
    public String readFile(@ToolParam(description = "Name of a file to read, relative to the task workspace") String fileName) {
        try {
            Path path = sandbox.resolveWithin(fileDir, fileName);
            if (!Files.isRegularFile(path)) {
                return "Error reading file: file not found";
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.length() > MAX_READ_CHARS) {
                return content.substring(0, MAX_READ_CHARS) + "\n...[truncated]";
            }
            return content;
        } catch (IllegalArgumentException | IOException exception) {
            return "Error reading file: " + exception.getMessage();
        }
    }

    @Tool(description = "Write content to a file inside the current task workspace")
    public String writeFile(@ToolParam(description = "Name of the file to write, relative to the task workspace") String fileName,
                            @ToolParam(description = "Content to write to the file") String content) {
        try {
            if (content == null) content = "";
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_WRITE_BYTES) {
                return "Error writing to file: content exceeds the 5 MB limit";
            }
            Path path = sandbox.resolveWithin(fileDir, fileName);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return "File written successfully to: " + workDir.relativize(path);
        } catch (IllegalArgumentException | IOException exception) {
            return "Error writing to file: " + exception.getMessage();
        }
    }
}
