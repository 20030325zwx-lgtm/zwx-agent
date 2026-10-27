package com.zwx.zwxagent.capability.tools;

import com.zwx.zwxagent.platform.workspace.ProjectExecutionLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ProjectGitCommitToolTest {

    @TempDir
    Path project;
    ProjectGitCommitTool tool;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(project.resolve("README.md"), "before\n");
        run("git", "init", "-q");
        run("git", "add", "README.md");
        run("git", "-c", "user.name=Aster Test", "-c", "user.email=aster@example.invalid",
                "commit", "-qm", "initial");
        tool = new ProjectGitCommitTool("project-1", project, "run-1",
                mock(ProjectExecutionLockService.class), false);
    }

    @Test
    void commitsCheckoutChangesWithoutHooksOrPush() throws Exception {
        Files.writeString(project.resolve("README.md"), "after\n");
        String result = tool.commitProjectChanges("Update readme");
        assertTrue(result.contains("Commit created:"));
        assertTrue(runOutput("git", "log", "-1", "--pretty=%s").contains("Update readme"));
    }

    @Test
    void rejectsSensitiveFiles() throws Exception {
        Files.writeString(project.resolve(".env.local"), "SECRET=value\n");
        assertTrue(tool.commitProjectChanges("Do not commit secret").contains("sensitive file"));
    }

    private void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(project.toFile()).start();
        if (process.waitFor() != 0) throw new IllegalStateException("git fixture command failed");
    }

    private String runOutput(String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(project.toFile()).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException("git fixture command failed");
        return output;
    }
}
