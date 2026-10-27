package com.zwx.zwxagent.platform.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectPathGuardTest {

    @TempDir
    Path root;

    @Test
    void rejectsTraversalAndSymlink() throws Exception {
        ProjectPathGuard guard = new ProjectPathGuard(root);
        assertThrows(IllegalArgumentException.class, () -> guard.resolveExisting("../outside"));
        assertThrows(IllegalArgumentException.class, () -> guard.resolveExisting("/tmp"));

        Path outside = Files.createTempDirectory("project-guard-outside");
        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
            assertThrows(IllegalArgumentException.class, () -> guard.resolveExisting("escape"));
        } finally {
            Files.deleteIfExists(root.resolve("escape"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void detectsSensitiveProjectFiles() throws Exception {
        ProjectPathGuard guard = new ProjectPathGuard(root);
        Path env = Files.writeString(root.resolve(".env.local"), "secret");
        assertTrue(guard.isSensitive(env));
    }
}
