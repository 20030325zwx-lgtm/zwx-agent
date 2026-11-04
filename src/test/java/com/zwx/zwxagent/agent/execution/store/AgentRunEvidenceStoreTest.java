package com.zwx.zwxagent.agent.execution.store;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunEvidenceStoreTest {

    @Test
    void recordWritesProducerKindAndResult() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new AgentRunEvidenceStore(jdbc);
        UUID id = store.record("run-1", "t", "u", null, 7L, AgentRunEvidenceStore.KIND_COMMAND_EXIT,
                AgentRunEvidenceStore.PRODUCER_AGENT, null, null, null, null,
                Map.of("exitCode", 1), "tests-pass");
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbc).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("agent_run_evidence"));
        assertEquals(AgentRunEvidenceStore.PRODUCER_AGENT, args.getValue()[7]);
        assertEquals(7L, args.getValue()[5]);
        assertEquals(id, args.getValue()[0]);
    }

    @Test
    void updateVerificationRejectsUnknownStatus() {
        var store = new AgentRunEvidenceStore(mock(JdbcTemplate.class));
        assertThrows(IllegalArgumentException.class,
                () -> store.updateVerification("run", "t", "u", UUID.randomUUID(), "MAYBE", null));
    }

    @Test
    void updateVerificationClearsVerifiedAtWhenResetToNotRun() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new AgentRunEvidenceStore(jdbc);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertTrue(store.updateVerification("run-1", "t", "u", UUID.randomUUID(),
                AgentRunEvidenceStore.VERIFICATION_NOT_RUN, null));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("verified_at = CASE WHEN ? = 'NOT_RUN' THEN NULL"), "重置时清除验证时间");
        assertFalse(sql.getValue().contains("verification_status = 'PASS'"), "状态由参数提供");
    }
}
