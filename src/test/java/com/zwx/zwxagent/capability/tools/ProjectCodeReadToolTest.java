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

    private void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(project.toFile()).start();
        if (process.waitFor() != 0) throw new IllegalStateException("git fixture command failed");
    }
}
