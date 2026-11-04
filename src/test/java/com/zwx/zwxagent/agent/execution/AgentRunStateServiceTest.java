package com.zwx.zwxagent.agent.execution;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A2 状态机回归：控制请求 CAS、认领派发语义、安全点停机、终态 fencing 防迟到写、同任务续答。
 */
class AgentRunStateServiceTest {

    private AgentRunStateService service(JdbcTemplate jdbc) {
        return new AgentRunStateService(jdbc, 90, 3);
    }

    @Test
    void claimUsesSkipLockedAndIncrementsFencing() {
        var jdbc = mock(JdbcTemplate.class);
        var service = service(jdbc);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<AgentRunStateService.ClaimedRun>>any(), eq("owner-1"), eq("90")))
                .thenReturn(List.of(new AgentRunStateService.ClaimedRun("run-1", "t", "u", "c",
                        1, 5, 0, "任务", "", "", "EDIT", null, null, null)));
        Optional<AgentRunStateService.ClaimedRun> claimed = service.claim("owner-1");
        assertTrue(claimed.isPresent());
        assertEquals("run-1", claimed.get().id());
        assertEquals(5, claimed.get().fencingToken());
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), org.mockito.ArgumentMatchers.<RowMapper<AgentRunStateService.ClaimedRun>>any(), eq("owner-1"), eq("90"));
        assertTrue(sql.getValue().contains("FOR UPDATE SKIP LOCKED"), "多执行器认领必须 SKIP LOCKED");
        assertTrue(sql.getValue().contains("fencing_token = fencing_token + 1"), "认领必须递增 fencing");
        assertTrue(sql.getValue().contains("engine_version = 2"), "派发器只认领新引擎 run");
    }

    @Test
    void requestControlValidatesAndCasGuards() {
        var jdbc = mock(JdbcTemplate.class);
        var service = service(jdbc);
        assertThrows(IllegalArgumentException.class,
                () -> service.requestControl("run", "t", "u", "RESUME", null));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1).thenReturn(0);
        assertTrue(service.requestControl("run", "t", "u",
                AgentRunStateService.CONTROL_PAUSE_REQUESTED, 7L));
        assertFalse(service.requestControl("run", "t", "u",
                AgentRunStateService.CONTROL_CANCEL_REQUESTED, 7L), "版本冲突必须拒绝");
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("control_state = ?"), "控制请求只写 control_state");
        assertTrue(sql.getValue().contains("phase IN ('QUEUED', 'WORKING')"), "终态 run 不可控制");
    }

    @Test
    void safePointAppliesPauseThenReportsStop() {
        var jdbc = mock(JdbcTemplate.class);
        var service = service(jdbc);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<ResultSetExtractor<String>>any(),
                eq("run-1"), eq("owner-1"), eq(5L), eq(2))).thenReturn("PAUSE_REQUESTED").thenReturn(null);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertTrue(service.shouldStopAtSafePoint("run-1", "owner-1", 5L), "暂停请求必须在安全点生效");
        assertFalse(service.shouldStopAtSafePoint("run-1", "owner-1", 5L), "无控制请求不停止");
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("phase = 'STOPPED'"));
        assertTrue(sql.getValue().contains("fencing_token = ?"), "停机必须携带 fencing 防迟到写");
    }

    @Test
    void completeIsCasOnOwnerAndFencing() {
        var jdbc = mock(JdbcTemplate.class);
        var service = service(jdbc);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1).thenReturn(0);
        assertTrue(service.complete("run-1", "owner-1", 5L, "COMPLETED", "答案", null));
        assertFalse(service.complete("run-1", "owner-1", 5L, "COMPLETED", "答案", null), "迟到回写必须拒绝");
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("owner_instance = ? AND fencing_token = ?"));
        assertTrue(sql.getValue().contains("AND phase = 'WORKING'"));
    }

    @Test
    void requeueAfterAnswerMovesWaitingRunToQueued() {
        var jdbc = mock(JdbcTemplate.class);
        var service = service(jdbc);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertTrue(service.requeueAfterAnswer("run-1", "t", "u", "本周"));
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("phase = 'QUEUED'"));
        assertTrue(sql.getValue().contains("history_context = COALESCE(history_context, '') || ?"), "答复并入历史，不重写原请求");
        assertTrue(String.valueOf(args.getValue()[0]).contains("本周"));
    }

    @Test
    void reapExpiredLeasesRequeuesThenBlocks() {
        var jdbc = mock(JdbcTemplate.class);
        var service = service(jdbc);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        assertEquals(1, service.reapExpiredLeases());
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getAllValues().get(0).contains("control_state <> 'NONE'"), "排队中的控制请求直接终态化");
        assertTrue(sql.getAllValues().get(1).contains("resume_count < ?"), "重试必须有界");
        assertTrue(sql.getAllValues().get(2).contains("phase = 'BLOCKED'"), "超限转阻塞");
    }
}
