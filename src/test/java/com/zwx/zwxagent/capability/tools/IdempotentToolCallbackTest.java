package com.zwx.zwxagent.capability.tools;

import com.zwx.zwxagent.agent.execution.AgentToolCallLedgerService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class IdempotentToolCallbackTest {

    @Test
    void replayedCallDoesNotInvokeDelegate() {
        AgentToolCallLedgerService ledger = Mockito.mock(AgentToolCallLedgerService.class);
        ToolCallback delegate = tool("webSearch");
        Mockito.when(ledger.begin("run-1", "webSearch", "{}"))
                .thenReturn(new AgentToolCallLedgerService.Decision(
                        AgentToolCallLedgerService.Mode.REPLAY, 1L, "cached result", null));

        ToolCallback wrapped = IdempotentToolCallback.wrapAll("run-1", new ToolCallback[]{delegate}, ledger)[0];
        Assertions.assertEquals("cached result", wrapped.call("{}"));
        Mockito.verify(delegate, Mockito.never()).call(anyString());
    }

    @Test
    void unknownOutcomeBlocksDuplicateSideEffect() {
        AgentToolCallLedgerService ledger = Mockito.mock(AgentToolCallLedgerService.class);
        ToolCallback delegate = tool("writeFile");
        Mockito.when(ledger.begin("run-1", "writeFile", "{}"))
                .thenReturn(new AgentToolCallLedgerService.Decision(
                        AgentToolCallLedgerService.Mode.UNKNOWN, 2L, null, "结果未知，禁止重放"));

        ToolCallback wrapped = IdempotentToolCallback.wrapAll("run-1", new ToolCallback[]{delegate}, ledger)[0];
        Assertions.assertEquals("结果未知，禁止重放", wrapped.call("{}"));
        Mockito.verify(delegate, Mockito.never()).call(anyString());
    }

    @Test
    void successfulCallIsRecordedInLedger() {
        AgentToolCallLedgerService ledger = Mockito.mock(AgentToolCallLedgerService.class);
        ToolCallback delegate = tool("webSearch");
        Mockito.when(delegate.call("{\"query\":\"x\"}")).thenReturn("search result");
        Mockito.when(ledger.begin("run-1", "webSearch", "{\"query\":\"x\"}"))
                .thenReturn(new AgentToolCallLedgerService.Decision(
                        AgentToolCallLedgerService.Mode.EXECUTE, 7L, null, null));

        ToolCallback wrapped = IdempotentToolCallback.wrapAll("run-1", new ToolCallback[]{delegate}, ledger)[0];
        Assertions.assertEquals("search result", wrapped.call("{\"query\":\"x\"}"));
        Mockito.verify(ledger).succeed(7L, "search result");
    }

    @Test
    void failedCallIsRecordedAndRethrown() {
        AgentToolCallLedgerService ledger = Mockito.mock(AgentToolCallLedgerService.class);
        ToolCallback delegate = tool("downloadResource");
        Mockito.when(delegate.call("{}")).thenThrow(new IllegalStateException("download failed"));
        Mockito.when(ledger.begin("run-1", "downloadResource", "{}"))
                .thenReturn(new AgentToolCallLedgerService.Decision(
                        AgentToolCallLedgerService.Mode.EXECUTE, 9L, null, null));

        ToolCallback wrapped = IdempotentToolCallback.wrapAll("run-1", new ToolCallback[]{delegate}, ledger)[0];
        Assertions.assertThrows(IllegalStateException.class, () -> wrapped.call("{}"));
        Mockito.verify(ledger).fail(eq(9L), anyString());
    }

    @Test
    void workerCallCarriesWorkerIdentityAndNotifiesOnBothTransitions() {
        AgentToolCallLedgerService ledger = Mockito.mock(AgentToolCallLedgerService.class);
        ToolCallback delegate = tool("searchWeb");
        Mockito.when(delegate.call("{}")) .thenReturn("result");
        Mockito.when(ledger.begin("run-1", "worker-2-researcher", "searchWeb", "{}"))
                .thenReturn(new AgentToolCallLedgerService.Decision(
                        AgentToolCallLedgerService.Mode.EXECUTE, 12L, null, null));
        java.util.List<Long> changes = new java.util.ArrayList<>();
        ToolCallback wrapped = IdempotentToolCallback.wrapAll("run-1", new ToolCallback[]{delegate}, ledger,
                changes::add)[0];

        ToolCallWorkerContext.set("worker-2-researcher");
        try { Assertions.assertEquals("result", wrapped.call("{}")); }
        finally { ToolCallWorkerContext.clear(); }

        Assertions.assertEquals(java.util.List.of(12L, 12L), changes);
        Mockito.verify(ledger).succeed(12L, "result");
    }

    private static ToolCallback tool(String name) {
        ToolCallback callback = Mockito.mock(ToolCallback.class);
        ToolDefinition definition = Mockito.mock(ToolDefinition.class);
        Mockito.when(definition.name()).thenReturn(name);
        Mockito.when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }
}
