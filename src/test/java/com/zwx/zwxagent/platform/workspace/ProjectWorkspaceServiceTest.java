package com.zwx.zwxagent.platform.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ProjectWorkspaceServiceTest {

    @TempDir
    Path tempDir;

    private ProjectWorkspaceService service() {
        return new ProjectWorkspaceService(mock(JdbcTemplate.class), tempDir.toString());
    }

    @Test
    void createsOnlyOneDirectChildUnderConfiguredRoot() throws Exception {
        Path created = service().createDirectChild("demo-project");
        assertTrue(Files.isDirectory(created));
        assertEquals(tempDir.toRealPath(), created.getParent());
    }

    @Test
    void rejectsTraversalAbsoluteAndNestedPaths() {
        ProjectWorkspaceService service = service();
        for (String value : new String[]{"../outside", "/tmp/outside", "a/b", "a\\b", ".", ".."}) {
            assertThrows(IllegalArgumentException.class, () -> service.createDirectChild(value), value);
        }
    }

    @Test
    void rejectsExistingDirectoryAndSymlink() throws Exception {
        Files.createDirectory(tempDir.resolve("existing"));
        assertThrows(ProjectWorkspaceConflictException.class, () -> service().createDirectChild("existing"));

        Path outside = Files.createDirectory(tempDir.resolveSibling(tempDir.getFileName() + "-outside"));
        try {
            Files.createSymbolicLink(tempDir.resolve("escape"), outside);
            assertThrows(IllegalArgumentException.class, () -> service().createDirectChild("escape"));
        } finally {
            Files.deleteIfExists(tempDir.resolve("escape"));
            Files.deleteIfExists(outside);
        }
    }
}
