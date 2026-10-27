package com.zwx.zwxagent.capability.tools;

import com.zwx.zwxagent.platform.workspace.ProjectExecutionLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ProjectCodeWriteToolTest {

    @TempDir
    Path project;
    ProjectExecutionLockService lockService;
    ProjectCodeWriteTool tool;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/App.java"), "class App { String value = \"before\"; }\n");
        run("git", "init", "-q");
        run("git", "add", "src/App.java");
        run("git", "-c", "user.name=Aster Test", "-c", "user.email=aster@example.invalid",
                "commit", "-qm", "initial");
        lockService = mock(ProjectExecutionLockService.class);
        tool = new ProjectCodeWriteTool("project-1", "demo", project, "run-1", lockService);
    }

    @Test
    void appliesCheckedPatchAndReturnsDiff() throws Exception {
        String patch = """
                diff --git a/src/App.java b/src/App.java
                --- a/src/App.java
                +++ b/src/App.java
                @@ -1 +1 @@
                -class App { String value = "before"; }
                +class App { String value = "after"; }
                """;

        String result = tool.applyProjectPatch(patch);

        assertTrue(result.contains("Patch applied successfully"));
        assertTrue(result.contains("+class App { String value = \"after\"; }"));
        assertEquals("class App { String value = \"after\"; }\n", Files.readString(project.resolve("src/App.java")));
        verify(lockService).acquire("project-1", "run-1");
    }

    @Test
    void rejectsTraversalBeforeTakingWriteLease() {
        String patch = """
                diff --git a/../outside b/../outside
                --- a/../outside
                +++ b/../outside
                @@ -0,0 +1 @@
                +bad
                """;
        assertTrue(tool.applyProjectPatch(patch).startsWith("Error"));
        verifyNoInteractions(lockService);
    }

    private void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(project.toFile()).start();
        if (process.waitFor() != 0) throw new IllegalStateException("git fixture command failed");
    }
}
