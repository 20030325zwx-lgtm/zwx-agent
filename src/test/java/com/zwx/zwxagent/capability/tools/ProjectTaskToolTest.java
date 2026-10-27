package com.zwx.zwxagent.capability.tools;

import com.zwx.zwxagent.platform.workspace.ProjectExecutionLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ProjectTaskToolTest {

    @TempDir
    Path project;
    ProjectExecutionLockService lockService;
    ProjectTaskTool tool;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(project.resolve("package.json"), """
                {"scripts":{"build":"echo build-ok","lint":"echo lint-ok"}}
                """);
        lockService = mock(ProjectExecutionLockService.class);
        tool = new ProjectTaskTool("project-1", project, "run-1", lockService, 30);
    }

    @Test
    void detectsAndRunsOnlyKnownTasks() {
        String tasks = tool.listProjectTasks();
        assertTrue(tasks.contains("npm:root:build"));
        assertTrue(tasks.contains("npm:root:lint"));

        String result = tool.runProjectTask("npm:root:lint");
        assertTrue(result.contains("completed"));
        assertTrue(result.contains("lint-ok"));
        verify(lockService).acquire("project-1", "run-1");
    }

    @Test
    void rejectsArbitraryTaskIds() {
        assertTrue(tool.runProjectTask("sh:rm").startsWith("Error"));
        verifyNoInteractions(lockService);
    }
}
