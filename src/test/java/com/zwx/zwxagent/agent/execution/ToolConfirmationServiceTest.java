package com.zwx.zwxagent.agent.execution;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A0 授权闭锁回归：确认决定必须先持久化成功，才允许在内存放行/拒绝；
 * 数据库写失败时决定整体失败，运行保持等待，不得凭内存状态放行工具。
 */
class ToolConfirmationServiceTest {

    @Test
    void decideAppliesMemoryOnlyWhenPersisted() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ToolConfirmationService service = new ToolConfirmationService(true, jdbc, null);
        service.registerRun("run-1");
        service.markPending("run-1", "terminal", "rm -rf /tmp/x");

        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertEquals(1, service.decide("run-1", "terminal", ToolConfirmationService.DECISION_APPROVED));
        assertTrue(service.isApproved("run-1", "terminal"));
        assertTrue(service.pendingOf("run-1").isEmpty());
    }

    @Test
    void decideDoesNotApproveInMemoryWhenPersistenceFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ToolConfirmationService service = new ToolConfirmationService(true, jdbc, null);
        service.registerRun("run-1");
        service.markPending("run-1", "terminal", "rm -rf /tmp/x");

        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        assertThrows(IllegalStateException.class,
                () -> service.decide("run-1", "terminal", ToolConfirmationService.DECISION_APPROVED));
        assertFalse(service.isApproved("run-1", "terminal"), "持久化失败不得在内存放行");
        assertEquals(1, service.pendingOf("run-1").size(), "待确认请求保持等待");
    }

    @Test
    void decideWithoutPersistedRowsLeavesPendingUntouched() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ToolConfirmationService service = new ToolConfirmationService(true, jdbc, null);
        service.registerRun("run-1");
        service.markPending("run-1", "terminal", "cmd");

        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertEquals(0, service.decide("run-1", "terminal", ToolConfirmationService.DECISION_APPROVED));
        assertFalse(service.isApproved("run-1", "terminal"), "未命中持久化行时不得放行");
        assertEquals(1, service.pendingOf("run-1").size());
    }
}
