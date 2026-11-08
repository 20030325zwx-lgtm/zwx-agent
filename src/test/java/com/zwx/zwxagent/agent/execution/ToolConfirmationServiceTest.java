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

    // ---- 审批超时：过期 pending 自动拒绝（保守方向），并同步内存状态 ----

    @Test
    void stalePendingExpiredAsDeniedAndMemorySynced() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ToolConfirmationService service = new ToolConfirmationService(true, jdbc, null, 60);
        service.registerRun("run-1");
        service.markPending("run-1", "executeTerminalCommand", "cmd");

        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of(
                java.util.Map.of("run_id", "run-1", "tool_name", "executeTerminalCommand")));
        java.util.Set<String> affected = service.expireStalePending();
        assertEquals(java.util.Set.of("run-1"), affected);
        assertTrue(service.isDenied("run-1", "executeTerminalCommand"), "超时按拒绝处理");
        assertTrue(service.pendingOf("run-1").isEmpty());
        assertFalse(service.isApproved("run-1", "executeTerminalCommand"));
    }

    @Test
    void expiryDisabledOrPersistenceFailureLeavesStateUntouched() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // timeout=0 视为关闭，不发 SQL
        assertEquals(java.util.Set.of(), new ToolConfirmationService(true, jdbc, null, 0).expireStalePending());
        // 落库失败整体放弃（A0 闭锁：决定必须先落库才生效）
        ToolConfirmationService service = new ToolConfirmationService(true, jdbc, null, 60);
        service.registerRun("run-1");
        service.markPending("run-1", "executeTerminalCommand", "cmd");
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        assertEquals(java.util.Set.of(), service.expireStalePending());
        assertEquals(1, service.pendingOf("run-1").size(), "落库失败时 pending 保持等待");
        assertFalse(service.isDenied("run-1", "executeTerminalCommand"));
    }

    @Test
    void hasPendingQueriesDatabaseAndFailsSafe() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ToolConfirmationService service = new ToolConfirmationService(true, jdbc, null, 60);
        when(jdbc.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class), any(Object[].class)))
                .thenReturn(java.util.List.of(0))
                .thenReturn(java.util.List.of(2))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        assertFalse(service.hasPending("run-1"), "无 pending 才允许自动恢复");
        assertTrue(service.hasPending("run-1"));
        assertTrue(service.hasPending("run-1"), "查询失败按仍有 pending 处理，宁可不自动恢复");
    }
}
