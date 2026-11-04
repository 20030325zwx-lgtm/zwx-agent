package com.zwx.zwxagent.agent.execution.store;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunInputStoreTest {

    @Test
    void recordRejectsMissingRequestKey() {
        var store = new AgentRunInputStore(mock(JdbcTemplate.class));
        assertThrows(IllegalArgumentException.class,
                () -> store.record("t", "u", "conv", "run", null, AgentRunInputStore.KIND_ANSWER,
                        Map.of("answer", "x"), " ", null, null));
    }

    @Test
    void recordIsIdempotentPerRequestKey() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new AgentRunInputStore(jdbc);
        var existing = new AgentRunInputStore.InputRecord(UUID.randomUUID(), "conv", "run", null, null,
                1, AgentRunInputStore.KIND_ANSWER, "{}", "req-1", AgentRunInputStore.STATUS_PENDING, 1,
                java.time.Instant.now());
        when(jdbc.query(anyString(), any(RowMapper.class), eq("t"), eq("u"), eq("req-1")))
                .thenReturn(List.of(existing));
        var result = store.record("t", "u", "conv", "run", null, AgentRunInputStore.KIND_ANSWER,
                Map.of("answer", "x"), "req-1", null, null);
        assertEquals(existing.id(), result.id());
        verify(jdbc, never()).update(anyString(), any(Object[].class)); // 重复请求不得新建行
    }

    @Test
    void acceptIsCasPendingToAcceptedOnce() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new AgentRunInputStore(jdbc);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1).thenReturn(0);
        assertTrue(store.accept("t", "u", UUID.randomUUID(), 1));
        assertFalse(store.accept("t", "u", UUID.randomUUID(), 1), "答复只可消费一次");
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(sql.capture(), args.capture());
        assertEquals("ACCEPTED", args.getAllValues().get(0)[0], "消费目标状态");
        assertTrue(sql.getValue().contains("AND version = ?"), "必须带版本锁");
        assertTrue(sql.getValue().contains("status = ?"), "必须校验当前状态");
    }

    @Test
    void markAppliedRequiresAcceptedState() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new AgentRunInputStore(jdbc);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertTrue(store.markApplied("t", "u", UUID.randomUUID(), null));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("status = ?"), "CAS 更新");
    }
}
