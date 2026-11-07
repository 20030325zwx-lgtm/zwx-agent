package com.zwx.zwxagent.agent.execution.store;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunEventLogTest {

    @Test
    void appendLocksRunRowAndAllocatesSeqByMaxPlusOne() {
        var jdbc = mock(JdbcTemplate.class);
        var log = new RunEventLog(jdbc);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        log.append("run-1", "t", "u", RunEventLog.TOOL_CALL, Map.of("callId", "c1"));

        verify(jdbc).queryForList(eq("SELECT id FROM agent_run WHERE id = ? AND tenant_id = ? AND user_id = ? FOR UPDATE"),
                eq("run-1"), eq("t"), eq("u"));
        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        var argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(1)).update(sqlCaptor.capture(), argsCaptor.capture());
        String insertSql = sqlCaptor.getValue();
        Object[] insertArgs = argsCaptor.getValue();
        assertTrue(insertSql.contains("COALESCE((SELECT MAX(e.seq)"), "seq 由 MAX+1 分配");
        assertTrue(insertSql.contains("FROM agent_run r"), "归属校验必须复检 run 所有权");
        assertEquals("tool/call", insertArgs[5]);
    }

    @Test
    void appendSkipsWithoutIdentityAndSwallowsFailures() {
        var jdbc = mock(JdbcTemplate.class);
        var log = new RunEventLog(jdbc);
        // 无归属身份：直接跳过，不触发任何 SQL
        log.append("run-1", null, "u", RunEventLog.RUN_START, Map.of());
        verify(jdbc, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
        // 写库异常：吞掉不外抛
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new RuntimeException("db down"));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> log.append("run-1", "t", "u", RunEventLog.RUN_START, Map.of()));
    }

    @Test
    void repairSynthesizesEventsForUnclosedToolCalls() {
        var jdbc = mock(JdbcTemplate.class);
        var log = new RunEventLog(jdbc);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        List<RunEventLog.EventRow> tail = List.of(
                new RunEventLog.EventRow(1, RunEventLog.RUN_START, "{}"),
                new RunEventLog.EventRow(2, RunEventLog.TOOL_CALL, "{\"callId\":\"c1\",\"tool\":\"web\"}"),
                new RunEventLog.EventRow(3, RunEventLog.TOOL_RESULT, "{\"callId\":\"c1\",\"tool\":\"web\"}"),
                new RunEventLog.EventRow(4, RunEventLog.TOOL_CALL, "{\"callId\":\"c2\",\"tool\":\"terminal\"}"),
                new RunEventLog.EventRow(5, RunEventLog.STEP_END, "{}"));
        when(jdbc.query(anyString(), any(RowMapper.class), eq("run-1"), anyInt())).thenReturn(
                // tail 内部会反转为升序，这里给降序模拟真实 SQL 返回
                List.of(tail.get(4), tail.get(3), tail.get(2), tail.get(1), tail.get(0)));

        List<String> notes = log.repairUnclosedToolCalls("run-1", "t", "u");

        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("terminal"), "修复摘要必须点名未闭合的工具");
        assertTrue(notes.get(0).contains("结果未知"));
        // 修复事件落日志
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(1)).update(anyString(), args.capture());
        assertEquals("tool/repair", args.getValue()[5]);
    }

    @Test
    void repairReturnsEmptyWhenAllCallsClosed() {
        var jdbc = mock(JdbcTemplate.class);
        var log = new RunEventLog(jdbc);
        List<RunEventLog.EventRow> tail = List.of(
                new RunEventLog.EventRow(1, RunEventLog.TOOL_CALL, "{\"callId\":\"c1\",\"tool\":\"web\"}"),
                new RunEventLog.EventRow(2, RunEventLog.TOOL_RESULT, "{\"callId\":\"c1\",\"tool\":\"web\"}"));
        // SQL 按 seq 降序返回，tail() 内部反转成升序
        when(jdbc.query(anyString(), any(RowMapper.class), eq("run-1"), anyInt())).thenReturn(
                List.of(tail.get(1), tail.get(0)));

        assertTrue(log.repairUnclosedToolCalls("run-1", "t", "u").isEmpty());
    }
}
