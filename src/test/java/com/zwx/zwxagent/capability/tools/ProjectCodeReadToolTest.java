package com.zwx.zwxagent.capability.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCodeReadToolTest {

    @TempDir
    Path project;
    ProjectCodeReadTool tool;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/App.java"), "class App { String greeting = \"hello\"; }\n");
        Files.writeString(project.resolve(".env"), "SECRET=hidden\n");
        Files.createDirectories(project.resolve("node_modules/pkg"));
        Files.writeString(project.resolve("node_modules/pkg/index.js"), "hidden dependency");
        run("git", "init", "-q");
        run("git", "add", "src/App.java");
        run("git", "-c", "user.name=Aster Test", "-c", "user.email=aster@example.invalid",
                "commit", "-qm", "initial");
        tool = new ProjectCodeReadTool("demo", project);
    }

    @Test
    void listsSearchesAndReadsOnlyProjectSource() {
        String tree = tool.listProjectTree(".");
        assertTrue(tree.contains("F src/App.java"));
        assertFalse(tree.contains(".env"));
        assertFalse(tree.contains("node_modules/pkg"));

        assertTrue(tool.searchProjectCode("greeting").contains("src/App.java:1"));
        assertTrue(tool.readProjectFile("src/App.java").contains("class App"));
        assertTrue(tool.readProjectFile(".env").contains("sensitive files"));
        assertTrue(tool.readProjectFile("../outside").startsWith("Error"));
    }

    @Test
    void reportsGitStatusAndDiff() throws Exception {
        Files.writeString(project.resolve("src/App.java"), "class App { String greeting = \"changed\"; }\n");
        assertTrue(tool.getProjectGitStatus().contains("src/App.java"));
        assertTrue(tool.getProjectDiff().contains("-class App"));
        assertTrue(tool.getProjectDiff().contains("+class App"));
    }

    @Test
    void rootListingKeepsTopLevelEntriesAheadOfDeepFiles() throws Exception {
        Files.createDirectories(project.resolve("aaa/deep"));
        for (int index = 0; index < 520; index++) {
            Files.writeString(project.resolve("aaa/deep/file-" + index + ".txt"), "data");
        }
        Files.createDirectories(project.resolve("zzz-project"));

        String tree = tool.listProjectTree(".");

        assertTrue(tree.contains("D zzz-project"));
        assertTrue(tree.contains("...[truncated at 500 entries]"));
    }

    @Test
    void fallsBackToJavaSearchWhenRipgrepIsUnavailable() {
        ProjectCodeReadTool withoutRipgrep = new ProjectCodeReadTool("demo", project, "missing-rg-command");

        String result = withoutRipgrep.searchProjectCode("greeting");

        assertTrue(result.contains("src/App.java:1"));
        assertFalse(result.contains(".env"));
    }

    private void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(project.toFile()).start();
        if (process.waitFor() != 0) throw new IllegalStateException("git fixture command failed");
    }
}
