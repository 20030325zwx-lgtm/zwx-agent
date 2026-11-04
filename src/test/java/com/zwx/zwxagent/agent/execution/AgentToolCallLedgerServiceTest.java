package com.zwx.zwxagent.agent.execution;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A2 账本 invocation 路径回归（方案 §5.2）：重放幂等、在途等待不误判 UNKNOWN、
 * 在途等待不改写账本行。
 */
class AgentToolCallLedgerServiceTest {

    @Test
    void beginInvocationExecutesWhenInsertWins() {
        var jdbc = mock(JdbcTemplate.class);
        var ledger = new AgentToolCallLedgerService(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(101L);
        var decision = ledger.beginInvocation("run-1", null, "worker-1#call-1", "worker-1", "terminal", "ls");
        assertEquals(AgentToolCallLedgerService.Mode.EXECUTE, decision.mode());
        assertEquals(101L, decision.id());
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), eq(Long.class), any(Object[].class));
        assertTrue(sql.getValue().contains("invocation_key"), "写入必须携带 invocation 身份");
        assertTrue(sql.getValue().contains("step_id"), "写入必须可归属步骤");
    }

    @Test
    void beginInvocationReplaysSucceededResult() {
        var jdbc = mock(JdbcTemplate.class);
        var ledger = new AgentToolCallLedgerService(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<AgentToolCallLedgerService.Decision>>any(),
                any(Object[].class)))
                .thenReturn(List.of(new AgentToolCallLedgerService.Decision(
                        AgentToolCallLedgerService.Mode.REPLAY, 7L, "ok", null)));
        var decision = ledger.beginInvocation("run-1", null, "worker-1#call-1", "worker-1", "terminal", "ls");
        assertEquals(AgentToolCallLedgerService.Mode.REPLAY, decision.mode());
        assertEquals("ok", decision.cachedResult());
    }

    @Test
    void beginInvocationWaitsForInFlightWithoutMarkingUnknown() {
        var jdbc = mock(JdbcTemplate.class);
        var ledger = new AgentToolCallLedgerService(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<AgentToolCallLedgerService.Decision>>any(),
                any(Object[].class)))
                .thenReturn(List.of(new AgentToolCallLedgerService.Decision(
                        AgentToolCallLedgerService.Mode.PENDING_IN_FLIGHT, 9L, null,
                        "同一调用正在另一个执行方处理中，本次未重复执行。")));
        var decision = ledger.beginInvocation("run-1", null, "worker-1#call-2", "worker-1", "terminal", "ls");
        assertEquals(AgentToolCallLedgerService.Mode.PENDING_IN_FLIGHT, decision.mode());
        // 在途调用不得被改写为 UNKNOWN（方案明确禁止）
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
