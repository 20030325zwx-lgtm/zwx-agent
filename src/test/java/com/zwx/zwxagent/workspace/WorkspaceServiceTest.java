package com.zwx.zwxagent.workspace;

import com.zwx.zwxagent.tools.ToolSandbox;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class WorkspaceServiceTest {

    @TempDir
    Path tempDir;

    private WorkspaceService service() {
        return new WorkspaceService(new ToolSandbox(), tempDir.toString());
    }

    @Test
    void conversationRootFollowsThreeLevelLayout() {
        WorkspaceService service = service();
        Path root = service.conversationRoot("default", "super", "conv-1");
        Assertions.assertEquals(tempDir.resolve("default").resolve("super").resolve("conv-1"), root);
        Assertions.assertTrue(Files.isDirectory(root));
        Assertions.assertTrue(service.sharedDir("default", "super").toString().endsWith("shared"));
    }

    @Test
    void rejectsInvalidComponents() {
        WorkspaceService service = service();
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.conversationRoot("../etc", "super", "c"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.conversationRoot("default", "super", " "));
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.conversationRoot("default", "super", null));
    }

    @Test
    void resolveWithinBlocksTraversal() {
        WorkspaceService service = service();
        Path root = service.conversationRoot("default", "super", "conv-1");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.resolveWithin(root, "../../secret.txt"));
        Assertions.assertDoesNotThrow(() -> service.resolveWithin(root, "file/ok.txt"));
    }

    @Test
    void listFilesReturnsRelativePathsSorted() throws Exception {
        WorkspaceService service = service();
        Path root = service.conversationRoot("default", "super", "conv-1");
        Files.writeString(root.resolve("b.txt"), "bb");
        Files.createDirectories(root.resolve("download"));
        Files.writeString(root.resolve("download").resolve("a.bin"), "a");

        List<WorkspaceService.FileEntry> files = service.listFiles("default", "super", "conv-1", 50);
        Assertions.assertEquals(List.of("b.txt", "download/a.bin"),
                files.stream().map(WorkspaceService.FileEntry::path).toList());
        Assertions.assertEquals(2, files.get(0).size());
        Assertions.assertEquals(1, files.get(1).size());
        Assertions.assertTrue(files.stream().allMatch(entry -> entry.modifiedAt() != null));
    }

    @Test
    void listFilesLimitsEntries() throws Exception {
        WorkspaceService service = service();
        Path root = service.conversationRoot("default", "super", "conv-1");
        for (int i = 0; i < 10; i++) Files.writeString(root.resolve("f" + i + ".txt"), "x");
        Assertions.assertEquals(3, service.listFiles("default", "super", "conv-1", 3).size());
    }
}
