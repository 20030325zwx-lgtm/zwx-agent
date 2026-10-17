package com.zwx.zwxagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

class TerminalOperationToolTest {

    @TempDir
    Path tempDir;

    @Test
    void allowlistedCommandRuns() {
        TerminalOperationTool tool = new TerminalOperationTool(tempDir, Set.of("echo"));
        String result = tool.executeTerminalCommand("echo sandbox-ok");
        Assertions.assertTrue(result.contains("sandbox-ok"));
    }

    @Test
    void nonAllowlistedCommandIsRejected() {
        TerminalOperationTool tool = new TerminalOperationTool(tempDir, Set.of("echo"));
        String result = tool.executeTerminalCommand("rm -rf /");
        Assertions.assertTrue(result.startsWith("Error executing command:"));
        Assertions.assertTrue(result.contains("not in the allowlist"));
    }

    @Test
    void metacharacterBypassIsRejected() {
        TerminalOperationTool tool = new TerminalOperationTool(tempDir, Set.of("ls"));
        // 原实现只查首 token 后交 sh -c，这类命令曾可绕过
        Assertions.assertTrue(tool.executeTerminalCommand("ls; cat /etc/passwd").contains("metacharacter"));
        Assertions.assertTrue(tool.executeTerminalCommand("ls && whoami").contains("metacharacter"));
    }

    @Test
    void absolutePathArgumentIsRejected() {
        TerminalOperationTool tool = new TerminalOperationTool(tempDir, Set.of("cat"));
        Assertions.assertTrue(tool.executeTerminalCommand("cat /etc/passwd").contains("absolute path"));
    }

    @Test
    void python3InlineCodeIsRejected() {
        TerminalOperationTool tool = new TerminalOperationTool(tempDir, Set.of("python3"));
        Assertions.assertTrue(tool.executeTerminalCommand("python3 -c \"print(1)\"").contains("python3 option"));
    }

    @Test
    void defaultAllowlistAppliesWhenUnset() {
        TerminalOperationTool tool = new TerminalOperationTool(tempDir, null);
        Assertions.assertFalse(tool.executeTerminalCommand("pwd").startsWith("Error"));
        Assertions.assertTrue(tool.executeTerminalCommand("curl http://example.com").contains("not in the allowlist"));
    }
}
