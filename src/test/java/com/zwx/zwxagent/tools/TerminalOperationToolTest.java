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
}
