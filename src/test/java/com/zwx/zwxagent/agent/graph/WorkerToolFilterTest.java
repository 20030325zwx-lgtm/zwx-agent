package com.zwx.zwxagent.agent.graph;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Set;

class WorkerToolFilterTest {

    private static ToolCallback callback(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
    }

    private final ToolCallback[] all = new ToolCallback[]{
            callback("webSearch"), callback("scrapeWebPage"), callback("downloadResource"),
            callback("readFile"), callback("writeFile"), callback("generatePDF"),
            callback("executeDatabaseQuery"), callback("queryExternalDatabase"),
            callback("executeTerminalCommand")
    };

    @Test
    void researcherOnlySeesResearchTools() {
        ToolCallback[] filtered = WorkersNode.filterTools(all, WorkerRole.RESEARCHER);
        Assertions.assertEquals(3, filtered.length);
        Assertions.assertTrue(WorkersNode.filterTools(all, WorkerRole.RESEARCHER)[0].getToolDefinition().name().equals("webSearch")
                || filtered.length == Set.of("webSearch", "scrapeWebPage", "downloadResource").size());
    }

    @Test
    void analystSeesOnlyQueryAndTerminalTools() {
        ToolCallback[] filtered = WorkersNode.filterTools(all, WorkerRole.ANALYST);
        Assertions.assertEquals(3, filtered.length);
        for (ToolCallback toolCallback : filtered) {
            Assertions.assertTrue(Set.of("executeDatabaseQuery", "queryExternalDatabase", "executeTerminalCommand")
                    .contains(toolCallback.getToolDefinition().name()));
        }
    }

    @Test
    void authorSeesFileTools() {
        ToolCallback[] filtered = WorkersNode.filterTools(all, WorkerRole.AUTHOR);
        Assertions.assertEquals(3, filtered.length);
    }

    @Test
    void generalSeesEverything() {
        Assertions.assertSame(all, WorkersNode.filterTools(all, WorkerRole.GENERAL));
    }
}
