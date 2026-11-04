package com.zwx.zwxagent.platform.workspace;

import com.zwx.zwxagent.capability.tools.ToolSandbox;
import com.zwx.zwxagent.platform.desktop.DesktopDirectoryGrantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 桌面端产物导出：授权校验、落盘、重名不覆盖。 */
class WorkspaceFileExportServiceTest {

    @TempDir
    Path workspace;
    @TempDir
    Path target;

    private WorkspaceService workspaceService;
    private ToolSandbox toolSandbox;
    private DesktopDirectoryGrantService grants;
    private WorkspaceFileExportService service;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        toolSandbox = mock(ToolSandbox.class);
        grants = mock(DesktopDirectoryGrantService.class);
        when(grants.active()).thenReturn(true);
        when(workspaceService.conversationRoot(anyString(), anyString(), anyString())).thenReturn(workspace);
        when(workspaceService.resolveWithin(any(), anyString()))
                .thenAnswer(invocation -> workspace.resolve((String) invocation.getArgument(1)).normalize());
        when(toolSandbox.scopeDir(anyString())).thenThrow(new IllegalArgumentException("no legacy dir"));
        service = new WorkspaceFileExportService(workspaceService, toolSandbox, grants);
    }

    @Test
    void exportsFileIntoGrantedDirectory() throws Exception {
        Files.writeString(workspace.resolve("report.md"), "# 报告");
        List<WorkspaceFileExportService.ExportResult> results =
                service.export("t", "super", "c", List.of("report.md"), target.toString(), false);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).to()).isEqualTo(target.resolve("report.md").toString());
        assertThat(Files.readString(target.resolve("report.md"))).isEqualTo("# 报告");
    }

    @Test
    void doesNotOverwriteExistingFileWithoutOverwriteFlag() throws Exception {
        Files.writeString(workspace.resolve("data.csv"), "a,b");
        Files.writeString(target.resolve("data.csv"), "existing");
        List<WorkspaceFileExportService.ExportResult> results =
                service.export("t", "super", "c", List.of("data.csv"), target.toString(), false);
        assertThat(results.get(0).to()).endsWith("data (1).csv");
    }

    @Test
    void overwritesWhenRequested() throws Exception {
        Files.writeString(workspace.resolve("data.csv"), "new");
        Files.writeString(target.resolve("data.csv"), "old");
        List<WorkspaceFileExportService.ExportResult> results =
                service.export("t", "super", "c", List.of("data.csv"), target.toString(), true);
        assertThat(results.get(0).to()).endsWith("data.csv");
        assertThat(Files.readString(target.resolve("data.csv"))).isEqualTo("new");
    }

    @Test
    void rejectsDirectoryWithoutGrant() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "x");
        doThrow(new IllegalArgumentException("目录未授权")).when(grants).requireGranted(target.toString());
        assertThatThrownBy(() -> service.export("t", "super", "c", List.of("a.txt"), target.toString(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未授权");
        assertThat(Files.exists(target.resolve("a.txt"))).isFalse();
    }

    @Test
    void rejectsMissingFileAndEmptyPaths() throws Exception {
        assertThatThrownBy(() -> service.export("t", "super", "c", List.of("missing.md"), target.toString(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件不存在");
        assertThatThrownBy(() -> service.export("t", "super", "c", List.of(), target.toString(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paths 不能为空");
    }

    @Test
    void rejectsWhenDesktopModeInactive() {
        when(grants.active()).thenReturn(false);
        assertThatThrownBy(() -> service.export("t", "super", "c", List.of("a.txt"), target.toString(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("桌面端");
    }
}
