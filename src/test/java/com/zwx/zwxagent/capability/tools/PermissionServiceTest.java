package com.zwx.zwxagent.capability.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionServiceTest {

    private final PermissionService service = new PermissionService(null,
            "applyProjectPatch,runProjectTask,commitProjectChanges,executeTerminalCommand,"
                    + "executeDatabaseQuery,queryExternalDatabase,downloadResource,mcp:*");

    private PermissionService.Decision decide(PermissionMode mode, String tool) {
        return service.decide(mode, List.of(), tool);
    }

    @Test
    void planModeAllowsOnlyReadOnlyTools() {
        assertEquals(PermissionService.Decision.ALLOW, decide(PermissionMode.PLAN, "readFile"));
        assertEquals(PermissionService.Decision.ALLOW, decide(PermissionMode.PLAN, "searchWeb"));
        assertEquals(PermissionService.Decision.DENY, decide(PermissionMode.PLAN, "writeFile"));
        assertEquals(PermissionService.Decision.DENY, decide(PermissionMode.PLAN, "executeTerminalCommand"));
        assertEquals(PermissionService.Decision.DENY, decide(PermissionMode.PLAN, "mcp:filesystem"));
    }

    @Test
    void buildModeAsksForWritesAndExecution() {
        assertEquals(PermissionService.Decision.ALLOW, decide(PermissionMode.BUILD, "readFile"));
        assertEquals(PermissionService.Decision.ASK, decide(PermissionMode.BUILD, "writeFile"));
        assertEquals(PermissionService.Decision.ASK, decide(PermissionMode.BUILD, "generatePDF"));
        assertEquals(PermissionService.Decision.ASK, decide(PermissionMode.BUILD, "applyProjectPatch"));
        assertEquals(PermissionService.Decision.ASK, decide(PermissionMode.BUILD, "executeTerminalCommand"));
    }

    @Test
    void editModeAllowsDeliverableWritesButAsksForExecution() {
        assertEquals(PermissionService.Decision.ALLOW, decide(PermissionMode.EDIT, "readFile"));
        assertEquals(PermissionService.Decision.ALLOW, decide(PermissionMode.EDIT, "writeFile"));
        assertEquals(PermissionService.Decision.ALLOW, decide(PermissionMode.EDIT, "generatePDF"));
        assertEquals(PermissionService.Decision.ASK, decide(PermissionMode.EDIT, "executeTerminalCommand"));
        assertEquals(PermissionService.Decision.ASK, decide(PermissionMode.EDIT, "applyProjectPatch"));
        assertEquals(PermissionService.Decision.ASK, decide(PermissionMode.EDIT, "mcp:filesystem"));
    }

    @Test
    void guardedModeOnlyAsksForDestructiveTools() {
        assertEquals(PermissionService.Decision.ALLOW, decide(PermissionMode.GUARDED, "writeFile"));
        assertEquals(PermissionService.Decision.ALLOW, decide(PermissionMode.GUARDED, "executeDatabaseQuery"));
        assertEquals(PermissionService.Decision.ALLOW, decide(PermissionMode.GUARDED, "mcp:filesystem"));
        assertEquals(PermissionService.Decision.ASK, decide(PermissionMode.GUARDED, "executeTerminalCommand"));
        assertEquals(PermissionService.Decision.ASK, decide(PermissionMode.GUARDED, "commitProjectChanges"));
        assertEquals(PermissionService.Decision.ASK, decide(PermissionMode.GUARDED, "queryExternalDatabase"));
    }

    @Test
    void yoloModeAllowsEverything() {
        assertEquals(PermissionService.Decision.ALLOW, decide(PermissionMode.YOLO, "executeTerminalCommand"));
        assertEquals(PermissionService.Decision.ALLOW, decide(PermissionMode.YOLO, "mcp:filesystem"));
        assertEquals(PermissionService.Decision.ALLOW, decide(PermissionMode.YOLO, "unknownTool"));
    }

    @Test
    void denyRuleWinsOverAllowRuleAndYolo() {
        List<PermissionService.Rule> rules = List.of(
                new PermissionService.Rule("executeTerminalCommand", true),
                new PermissionService.Rule("executeTerminalCommand", false));
        assertEquals(PermissionService.Decision.DENY,
                service.decide(PermissionMode.YOLO, rules, "executeTerminalCommand"));
    }

    @Test
    void allowRuleOverridesModeAsk() {
        List<PermissionService.Rule> rules = List.of(
                new PermissionService.Rule("executeTerminalCommand", true));
        assertEquals(PermissionService.Decision.ALLOW,
                service.decide(PermissionMode.EDIT, rules, "executeTerminalCommand"));
    }

    @Test
    void wildcardRulesMatchPrefix() {
        List<PermissionService.Rule> rules = List.of(new PermissionService.Rule("mcp:*", false));
        assertEquals(PermissionService.Decision.DENY,
                service.decide(PermissionMode.YOLO, rules, "mcp:filesystem"));
        assertEquals(PermissionService.Decision.ALLOW,
                service.decide(PermissionMode.YOLO, rules, "readFile"));
    }

    @Test
    void unknownToolsAreTreatedAsExecuteRisk() {
        assertEquals(ToolRisk.EXECUTE, service.riskOf("someFutureTool"));
        assertEquals(ToolRisk.EXECUTE, service.riskOf("mcp:unknown"));
        assertEquals(ToolRisk.DESTRUCTIVE, service.riskOf("executeTerminalCommand"));
        assertEquals(ToolRisk.WRITE, service.riskOf("writeFile"));
    }

    @Test
    void legacyRequiredToolsStillRaiseRiskToExecute() {
        PermissionService custom = new PermissionService(null, "writeFile");
        assertEquals(ToolRisk.EXECUTE, custom.riskOf("writeFile"));
        assertEquals(PermissionService.Decision.ASK,
                custom.decide(PermissionMode.EDIT, List.of(), "writeFile"));
    }

    @Test
    void missingModeFallsBackToDefault() {
        assertEquals(PermissionMode.DEFAULT, PermissionMode.parse(null));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.parse("not-a-mode"));
        assertEquals(PermissionMode.YOLO, PermissionMode.parse(" yolo "));
        assertEquals(PermissionService.Decision.ALLOW, service.decide(null, List.of(), "readFile"));
        assertEquals(PermissionService.Decision.ASK, service.decide(null, List.of(), "executeTerminalCommand"));
    }
}
