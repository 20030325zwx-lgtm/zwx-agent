package com.zwx.zwxagent.platform.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunWorkspaceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void createsIsolatedWorktreeForGitProject() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("source"));
        Files.writeString(source.resolve("README.md"), "base\n");
        run(source, "git", "init", "-q");
        run(source, "git", "add", "README.md");
        run(source, "git", "-c", "user.name=Aster Test", "-c", "user.email=aster@example.invalid",
                "commit", "-qm", "initial");

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProjectWorkspaceService projects = mock(ProjectWorkspaceService.class);
        when(projects.resolveForConversation("tenant", "user", "conversation"))
                .thenReturn(Optional.of(new ResolvedProjectWorkspace("project-1", "demo", source)));
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
        RunWorkspaceService service = new RunWorkspaceService(jdbc, projects, tempDir.resolve("worktrees").toString());

        RunWorkspaceContext context = service.resolve("tenant", "user", "conversation", "run-1").orElseThrow();
        assertEquals(RunWorkspaceContext.Mode.WORKTREE, context.mode());
        assertFalse(context.checkoutRoot().equals(source));
        assertTrue(Files.isRegularFile(context.checkoutRoot().resolve("README.md")));
        assertTrue(context.branchName().startsWith("aster/run-"));
        verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any());
    }

    private void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        if (process.waitFor() != 0) throw new IllegalStateException("git fixture command failed");
    }
}
