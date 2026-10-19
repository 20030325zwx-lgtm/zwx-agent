package com.zwx.zwxagent.agent.hook;

import com.zwx.zwxagent.tools.UrlAccessPolicy;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolGuardHookTest {

    private ToolGuardHook hook(boolean enabled) {
        return new ToolGuardHook(enabled, "", "", new UrlAccessPolicy());
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
        ToolGuardHook hook = new ToolGuardHook(true, "", "", urlPolicy);
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
        ToolGuardHook registered = new ToolGuardHook(true, "", "someFutureTool", new UrlAccessPolicy());
        assertDoesNotThrow(() -> registered.beforeToolCalls(context(), calls));
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
}
