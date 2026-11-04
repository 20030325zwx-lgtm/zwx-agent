package com.zwx.zwxagent.agent.execution.store;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunStepStoreTest {

    private AgentRunStepStore.StepDef step(String key, List<String> dependsOn) {
        return new AgentRunStepStore.StepDef(key, "标题-" + key, "researcher", null,
                Map.of("input", key), dependsOn, List.of());
    }

    @Test
    void createStepsValidatesDagAndInsertsPending() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new AgentRunStepStore(jdbc);
        var ids = store.createSteps("run-1", "t", "u", 0,
                List.of(step("plan", List.of()), step("execute", List.of("plan")), step("deliver", List.of("execute"))));
        assertEquals(3, ids.size());
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(3)).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("agent_run_step"));
        assertEquals("PENDING", args.getAllValues().get(2)[12], "新步骤必须从 PENDING 起步");
    }

    @Test
    void rejectsDuplicateKeysMissingDepsSelfDepsAndCycles() {
        var store = new AgentRunStepStore(mock(JdbcTemplate.class));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRunStepStore.validateDag(List.of(step("a", List.of()), step("a", List.of()))));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRunStepStore.validateDag(List.of(step("a", List.of("ghost")))));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRunStepStore.validateDag(List.of(step("a", List.of("a")))));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRunStepStore.validateDag(List.of(step("a", List.of("b")), step("b", List.of("a")))));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> AgentRunStepStore.validateDag(List.of(step("a", List.of()), step("b", List.of("a")))));
    }

    @Test
    void transitionIsCasOnStatusAndReportsConflict() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new AgentRunStepStore(jdbc);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1).thenReturn(0);
        assertTrue(store.transition("run-1", "t", "u", 0, "execute",
                AgentRunStepStore.STATUS_PENDING, AgentRunStepStore.STATUS_RUNNING));
        assertFalse(store.transition("run-1", "t", "u", 0, "execute",
                AgentRunStepStore.STATUS_PENDING, AgentRunStepStore.STATUS_RUNNING), "版本冲突必须返回 false");
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("state_version = state_version + 1"), "流转必须推进乐观锁版本");
    }
}
