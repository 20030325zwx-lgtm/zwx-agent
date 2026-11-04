package com.zwx.zwxagent.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

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
 * A2 事件序号分配回归：run 行存在时由 agent_run.event_seq 原子分配；无 run 行退回 MAX+1；
 * 幂等命中回滚序号占用。
 */
class AgentExecutionTraceServiceTest {

    @Test
    void recordAllocatesSequenceFromRunRowWhenPresent() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new AgentExecutionTraceService(jdbc, new ObjectMapper());
        when(jdbc.queryForObject(
                eq("UPDATE agent_run SET event_seq = event_seq + 1 WHERE id = ? RETURNING event_seq"),
                eq(Long.class), eq("run-1"))).thenReturn(5L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        int seq = service.record("run-1", "t", "u1", "super", "conv", "run", "run.created",
                Map.of("k", "v"), "run", "run:req-1");
        assertEquals(5, seq);
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("ON CONFLICT (run_id, dedupe_key)"), "幂等键必须参与冲突目标");
        assertEquals("run:req-1", args.getValue()[10]);
    }

    @Test
    void recordFallsBackToLegacyPathWithoutRunRow() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new AgentExecutionTraceService(jdbc, new ObjectMapper());
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("run-x")))
                .thenThrow(new EmptyResultDataAccessException(1));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(9);
        int seq = service.record("run-x", "t", null, "travel", "conv", "step", "节点完成", Map.of(), null, null);
        assertEquals(9, seq);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void recordRollsBackSequenceAllocationOnDedupeHit() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new AgentExecutionTraceService(jdbc, new ObjectMapper());
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("run-1"))).thenReturn(6L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0, 1);
        service.record("run-1", "t", "u", "super", "conv", "run", "重复事件", Map.of(), "run", "dup-1");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(sql.capture(), any(Object[].class));
        assertEquals("UPDATE agent_run SET event_seq = event_seq - 1 WHERE id = ?",
                sql.getAllValues().get(1), "幂等命中必须回滚序号占用");
    }
}
