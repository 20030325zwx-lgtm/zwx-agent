package com.zwx.zwxagent.agent.execution;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 超时拒绝后的自动恢复：仅恢复仍挂起（STOPPED/INTERRUPTED）且无剩余 pending 的 run。 */
class PendingConfirmationSweeperTest {

    private final ToolConfirmationService confirmationService = mock(ToolConfirmationService.class);
    private final AgentRunStateService stateService = mock(AgentRunStateService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private PendingConfirmationSweeper sweeper() {
        return new PendingConfirmationSweeper(confirmationService, stateService, jdbc);
    }

    private void stubRunRow(String phase, String status) {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "tenant_id", "default", "user_id", "user-1", "phase", phase, "status", status)));
    }

    @Test
    void expiredRunIsAutoResumedWhenSuspended() {
        when(confirmationService.expireStalePending()).thenReturn(Set.of("run-1"));
        when(confirmationService.hasPending("run-1")).thenReturn(false);
        stubRunRow("STOPPED", "INTERRUPTED");
        when(stateService.resume("run-1", "default", "user-1")).thenReturn(true);

        sweeper().sweep();
        verify(stateService).resume("run-1", "default", "user-1");
    }

    @Test
    void runWithRemainingPendingIsNotResumed() {
        when(confirmationService.expireStalePending()).thenReturn(Set.of("run-2"));
        when(confirmationService.hasPending("run-2")).thenReturn(true);

        sweeper().sweep();
        verify(stateService, never()).resume(anyString(), anyString(), anyString());
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void terminalOrWorkingRunsAreNotTouched() {
        when(confirmationService.expireStalePending()).thenReturn(Set.of("run-3", "run-4"));
        when(confirmationService.hasPending(anyString())).thenReturn(false);
        stubRunRow("DONE", "COMPLETED");
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("tenant_id", "default", "user_id", "user-1",
                        "phase", "DONE", "status", "COMPLETED")))
                .thenReturn(List.of(Map.of("tenant_id", "default", "user_id", "user-1",
                        "phase", "WORKING", "status", "RUNNING")));

        sweeper().sweep();
        verify(stateService, never()).resume(anyString(), anyString(), anyString());
    }

    @Test
    void resumeFailureIsContainedPerRun() {
        when(confirmationService.expireStalePending()).thenReturn(Set.of("run-5", "run-6"));
        when(confirmationService.hasPending(anyString())).thenReturn(false);
        stubRunRow("STOPPED", "INTERRUPTED");
        when(stateService.resume(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("dispatch down"))
                .thenReturn(true);

        sweeper().sweep();
        verify(stateService).resume("run-6", "default", "user-1");
    }
}
