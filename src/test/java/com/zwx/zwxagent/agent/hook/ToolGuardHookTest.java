package com.zwx.zwxagent.agent.hook;

import com.zwx.zwxagent.agent.execution.ToolConfirmationService;
import com.zwx.zwxagent.capability.mcp.McpToolRegistry;
import com.zwx.zwxagent.capability.tools.UrlAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolGuardHookTest {

    private ToolConfirmationService confirmationService() {
        // 空实现 JdbcTemplate：确认台账的读写全部落空，仅内存态参与断言
        return new ToolConfirmationService(true, "executeTerminalCommand,executeDatabaseQuery,downloadResource,mcp:*",
                mock(JdbcTemplate.class));
    }

    private ToolGuardHook hook(boolean enabled) {
        return new ToolGuardHook(enabled, "", "", new UrlAccessPolicy(), confirmationService());
    }

    private AgentRunContext context() {
        AgentRunContext context = AgentRunContext.builder().tenantId("default").agentKey("super")
                .conversationId("conv-1").build();
        context.currentStep(2);
        return context;
    }

    @Test
    void shellMetacharacterCommandIsAborted() {
        List<PlannedToolCall> calls = List.of(
                new PlannedToolCall("id-1", "function", "executeTerminalCommand", "{\"command\": \"ls; cat /etc/passwd\"}"));
        HookAbortException exception = assertThrows(HookAbortException.class,
                () -> hook(true).beforeToolCalls(context(), calls));
        assertTrue(exception.getMessage().contains("metacharacter"));
        assertTrue(exception.getMessage().contains("executeTerminalCommand"));
    }

    @Test
    void privateUrlIsAborted() {
        List<PlannedToolCall> scrape = List.of(
                new PlannedToolCall("id-1", "function", "scrapeWebPage", "{\"url\": \"http://127.0.0.1:8123/api/health\"}"));
        HookAbortException scrapeAbort = assertThrows(HookAbortException.class,
                () -> hook(true).beforeToolCalls(context(), scrape));
        assertTrue(scrapeAbort.getMessage().contains("scrapeWebPage"));

        List<PlannedToolCall> download = List.of(
                new PlannedToolCall("id-2", "function", "downloadResource",
                        "{\"url\": \"http://192.168.1.1/x\", \"fileName\": \"a.bin\"}"));
        assertThrows(HookAbortException.class, () -> hook(true).beforeToolCalls(context(), download));

        List<PlannedToolCall> externalDatabase = List.of(
                new PlannedToolCall("id-3", "function", "queryExternalDatabase",
                        "{\"databaseType\":\"postgresql\",\"host\":\"127.0.0.1\",\"port\":5432,\"sql\":\"SELECT 1\"}"));
        assertThrows(HookAbortException.class, () -> hook(true).beforeToolCalls(context(), externalDatabase));
    }

    @Test
    void absolutePathFileNameIsAborted() {
        List<PlannedToolCall> calls = List.of(
                new PlannedToolCall("id-1", "function", "writeFile",
                        "{\"fileName\": \"/etc/hosts\", \"content\": \"x\"}"));
        HookAbortException exception = assertThrows(HookAbortException.class,
                () -> hook(true).beforeToolCalls(context(), calls));
        assertTrue(exception.getMessage().contains("absolute path"));
    }

    @Test
    void legitimateCallsPass() {
        UrlAccessPolicy urlPolicy = mock(UrlAccessPolicy.class);
        when(urlPolicy.validateHttpUrl(anyString())).thenAnswer(invocation -> URI.create(invocation.getArgument(0)));
        ToolGuardHook hook = new ToolGuardHook(true, "", "", urlPolicy, confirmationService());
        List<PlannedToolCall> calls = List.of(
                new PlannedToolCall("id-1", "function", "readFile", "{\"fileName\": \"notes/report.txt\"}"),
                new PlannedToolCall("id-2", "function", "executeTerminalCommand", "{\"command\": \"grep -rn TODO .\"}"),
                new PlannedToolCall("id-3", "function", "scrapeWebPage", "{\"url\": \"https://example.com/post?a=1\"}"));
        assertDoesNotThrow(() -> hook.beforeToolCalls(context(), calls));
    }

    @Test
    void unknownToolRequiresExplicitRegistration() {
        List<PlannedToolCall> calls = List.of(
                new PlannedToolCall("id-1", "function", "someFutureTool", "{\"path\": \"/etc/passwd\"}"));
        assertThrows(HookAbortException.class, () -> hook(true).beforeToolCalls(context(), calls));
        ToolGuardHook registered = new ToolGuardHook(true, "", "someFutureTool", new UrlAccessPolicy(), confirmationService());
        assertDoesNotThrow(() -> registered.beforeToolCalls(context(), calls));
    }

    @Test
    void mcpToolRegisteredForTenantIsAllowed() {
        McpToolRegistry.replace("mcp-tenant", List.of("zwx_agent_parse_document"));
        AgentRunContext context = AgentRunContext.builder().tenantId("mcp-tenant").agentKey("super")
                .conversationId("conv-1").build();
        List<PlannedToolCall> calls = List.of(new PlannedToolCall("id-1", "function",
                "zwx_agent_parse_document", "{\"document_url\": \"https://example.com/a.pdf\"}"));
        assertDoesNotThrow(() -> hook(true).beforeToolCalls(context, calls));
    }

    @Test
    void disabledHookDoesNotBlock() {
        List<PlannedToolCall> calls = List.of(
                new PlannedToolCall("id-1", "function", "executeTerminalCommand", "{\"command\": \"ls; cat /etc/passwd\"}"));
        assertDoesNotThrow(() -> hook(false).beforeToolCalls(context(), calls));
    }

    @Test
    void unparseableArgumentsForGuardedToolAreAborted() {
        List<PlannedToolCall> calls = List.of(
                new PlannedToolCall("id-1", "function", "executeTerminalCommand", "not-json"));
        HookAbortException exception = assertThrows(HookAbortException.class,
                () -> hook(true).beforeToolCalls(context(), calls));
        assertTrue(exception.getMessage().contains("不是有效 JSON"));
    }

    @Test
    void malformedArgumentsForUnknownToolAreDenied() {
        List<PlannedToolCall> calls = List.of(
                new PlannedToolCall("id-1", "function", "someFutureTool", "not-json"));
        assertThrows(HookAbortException.class, () -> hook(true).beforeToolCalls(context(), calls));
    }

    @Test
    void databaseQueryMustBeReadOnlyAndSingleStatement() {
        List<PlannedToolCall> calls = List.of(new PlannedToolCall("id-1", "function", "executeDatabaseQuery",
                "{\"sql\":\"SELECT 1; DROP TABLE app_user\"}"));
        assertThrows(HookAbortException.class, () -> hook(true).beforeToolCalls(context(), calls));
    }

    @Test
    void rejectionLogsDoNotContainRawArguments() {
        String secret = "password-do-not-log";
        List<PlannedToolCall> calls = List.of(new PlannedToolCall("id-1", "function", "executeTerminalCommand",
                "{\"command\":\"ls; echo " + secret + "\"}"));
        assertThrows(HookAbortException.class, () -> hook(true).beforeToolCalls(context(), calls));
    }

    // ---- 副作用工具人工确认门禁：仅对注册过的 run 生效 ----

    @Test
    void registeredRunRequiringConfirmationSuspendsToolCall() {
        ToolConfirmationService service = confirmationService();
        AgentRunContext runContext = AgentRunContext.builder().tenantId("default").agentKey("super")
                .conversationId("conv-1").runId("run-42").build();
        service.registerRun("run-42");
        ToolGuardHook hook = new ToolGuardHook(true, "", "", new UrlAccessPolicy(), service);
        List<PlannedToolCall> calls = List.of(
                new PlannedToolCall("id-1", "function", "executeTerminalCommand", "{\"command\": \"ls\"}"));
        ToolConfirmationRequiredException exception = assertThrows(ToolConfirmationRequiredException.class,
                () -> hook.beforeToolCalls(runContext, calls));
        assertTrue(exception.getMessage().contains("确认"));
        org.junit.jupiter.api.Assertions.assertEquals(1, service.pendingOf("run-42").size());
        // 未注册的 run 不启用确认：同一调用直接放行
        assertDoesNotThrow(() -> hook(true).beforeToolCalls(context(), calls));
    }

    @Test
    void approvedToolPassesAndDeniedToolAborts() throws Exception {
        ToolConfirmationService service = confirmationService();
        // mock URL 策略使 downloadResource 通过 SSRF 预检，聚焦确认门禁本身
        UrlAccessPolicy urlPolicy = mock(UrlAccessPolicy.class);
        when(urlPolicy.validateHttpUrl(anyString())).thenAnswer(invocation -> URI.create(invocation.getArgument(0)));
        AgentRunContext runContext = AgentRunContext.builder().tenantId("default").agentKey("super")
                .conversationId("conv-1").runId("run-43").build();
        service.registerRun("run-43");
        service.markPending("run-43", "executeTerminalCommand", "keys=[command], chars=4");
        service.decide("run-43", "executeTerminalCommand", ToolConfirmationService.DECISION_APPROVED);
        ToolGuardHook hook = new ToolGuardHook(true, "", "", urlPolicy, service);
        List<PlannedToolCall> calls = List.of(
                new PlannedToolCall("id-1", "function", "executeTerminalCommand", "{\"command\": \"ls\"}"));
        assertDoesNotThrow(() -> hook.beforeToolCalls(runContext, calls));

        service.markPending("run-43", "downloadResource", "keys=[url, fileName], chars=30");
        service.decide("run-43", "downloadResource", ToolConfirmationService.DECISION_DENIED);
        List<PlannedToolCall> download = List.of(
                new PlannedToolCall("id-2", "function", "downloadResource",
                        "{\"url\": \"https://example.com/a.bin\", \"fileName\": \"a.bin\"}"));
        HookAbortException denied = assertThrows(HookAbortException.class,
                () -> hook.beforeToolCalls(runContext, download));
        assertTrue(denied.getMessage().contains("拒绝"), denied.getMessage());
    }

    @Test
    void confirmationDisabledSkipsGate() {
        ToolConfirmationService disabled = new ToolConfirmationService(false,
                "executeTerminalCommand", mock(JdbcTemplate.class));
        AgentRunContext runContext = AgentRunContext.builder().tenantId("default").agentKey("super")
                .conversationId("conv-1").runId("run-44").build();
        disabled.registerRun("run-44");
        ToolGuardHook hook = new ToolGuardHook(true, "", "", new UrlAccessPolicy(), disabled);
        List<PlannedToolCall> calls = List.of(
                new PlannedToolCall("id-1", "function", "executeTerminalCommand", "{\"command\": \"ls\"}"));
        assertDoesNotThrow(() -> hook.beforeToolCalls(runContext, calls));
    }
}
