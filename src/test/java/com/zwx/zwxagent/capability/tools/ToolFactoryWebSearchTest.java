package com.zwx.zwxagent.capability.tools;

import com.zwx.zwxagent.platform.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolFactoryWebSearchTest {

    private ToolFactory factory() throws Exception {
        ToolSandbox sandbox = mock(ToolSandbox.class);
        WorkspaceService workspace = mock(WorkspaceService.class);
        when(workspace.conversationRoot(anyString(), anyString(), anyString()))
                .thenReturn(Files.createTempDirectory("tool-factory-test"));
        return new ToolFactory(sandbox, workspace, mock(com.zwx.zwxagent.platform.workspace.ProjectWorkspaceService.class),
                mock(com.zwx.zwxagent.platform.workspace.ProjectExecutionLockService.class),
                mock(UrlAccessPolicy.class), "", "searchapi",
                false, 1024 * 1024, "", mock(DataSource.class), false, "", "", 20, 5, false);
    }

    private Set<String> names(ToolCallback[] tools) {
        return Arrays.stream(tools)
                .map(tool -> tool.getToolDefinition().name())
                .collect(Collectors.toSet());
    }

    @Test
    void removesBuiltInWebToolsWhenDisabled() throws Exception {
        Set<String> names = names(factory().createTools("tenant", "super", "conversation", false));

        assertFalse(names.contains("searchWeb"));
        assertFalse(names.contains("scrapeWebPage"));
        assertFalse(names.contains("downloadResource"));
        assertTrue(names.contains("writeFile"));
        assertTrue(names.contains("getCurrentTime"));
    }

    @Test
    void keepsBuiltInWebToolsWhenEnabled() throws Exception {
        Set<String> names = names(factory().createTools("tenant", "super", "conversation", true));

        assertTrue(names.contains("searchWeb"));
        assertTrue(names.contains("scrapeWebPage"));
        assertTrue(names.contains("downloadResource"));
    }
}
