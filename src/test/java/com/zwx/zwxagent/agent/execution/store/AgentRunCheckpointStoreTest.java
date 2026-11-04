package com.zwx.zwxagent.agent.execution.store;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunCheckpointStoreTest {

    @Test
    void appendAllocatesSequentialCheckpointNoWithinRunOwnership() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new AgentRunCheckpointStore(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("run-1"), eq("t"), eq("u"))).thenReturn(2L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        UUID id = store.append("run-1", "t", "u", null, 1, 0,
                Map.of("node", "planner"), Map.of("contract", "做一件事"), 42, null,
                AgentRunCheckpointStore.REASON_AFTER_STEP);

        var selectSql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).queryForObject(selectSql.capture(), eq(Long.class),
                eq("run-1"), eq("t"), eq("u"));
        assertTrue(selectSql.getValue().contains("COALESCE(MAX(c.checkpoint_no), 0) + 1"), "checkpoint_no 由 MAX+1 分配");

        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbc).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("FROM agent_run r"), "归属校验必须复检 run 所有权");
        assertEquals(2L, args.getValue()[5], "插入使用分配到的 checkpoint_no");
        assertEquals(id, args.getValue()[0]);
    }

    @Test
    void appendFailsWhenRunOwnershipDoesNotMatch() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new AgentRunCheckpointStore(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> store.append("run-x", "t", "u", null, 1, 0,
                Map.of(), Map.of(), 0, null, AgentRunCheckpointStore.REASON_WAITING));
    }

    @Test
    void contentHashIsStableSha256() {
        assertEquals(64, AgentRunCheckpointStore.sha256Hex("{}").length());
        assertEquals(AgentRunCheckpointStore.sha256Hex("abc"), AgentRunCheckpointStore.sha256Hex("abc"));
    }
}
