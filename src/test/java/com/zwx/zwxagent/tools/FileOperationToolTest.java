package com.zwx.zwxagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class FileOperationToolTest {

    @TempDir
    Path tempDir;

    @Test
    void writeAndReadFile() {
        ToolSandbox sandbox = new ToolSandbox();
        FileOperationTool tool = new FileOperationTool(sandbox, tempDir);
        String written = tool.writeFile("notes.txt", "hello world");
        Assertions.assertTrue(written.contains("File written successfully to:"));
        String read = tool.readFile("notes.txt");
        Assertions.assertEquals("hello world", read);
    }

    @Test
    void rejectPathTraversal() {
        ToolSandbox sandbox = new ToolSandbox();
        FileOperationTool tool = new FileOperationTool(sandbox, tempDir);
        String written = tool.writeFile("../../escape.txt", "nope");
        Assertions.assertTrue(written.startsWith("Error writing to file:"));
        String absolute = tool.writeFile(tempDir.getParent().resolve("escape-abs.txt").toString(), "nope");
        Assertions.assertTrue(absolute.startsWith("Error writing to file:"));
        Assertions.assertFalse(Files.exists(tempDir.getParent().resolve("escape.txt")));
    }

    @Test
    void readFileReturnsErrorForMissingFile() {
        ToolSandbox sandbox = new ToolSandbox();
        FileOperationTool tool = new FileOperationTool(sandbox, tempDir);
        String read = tool.readFile("missing.txt");
        Assertions.assertTrue(read.startsWith("Error reading file:"));
    }
}
