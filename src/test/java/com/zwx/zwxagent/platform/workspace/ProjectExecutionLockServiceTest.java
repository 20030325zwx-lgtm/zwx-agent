package com.zwx.zwxagent.platform.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectExecutionLockServiceTest {

    @Test
    void rejectsWhenAnotherRunOwnsTheProject() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("project-1"), eq("run-2"), eq(30)))
                .thenReturn(List.of());
        ProjectExecutionLockService service = new ProjectExecutionLockService(jdbc, 30);
        assertThrows(ProjectWorkspaceConflictException.class, () -> service.acquire("project-1", "run-2"));
    }

    @Test
    void releasesOnlyTheCurrentRunLease() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProjectExecutionLockService service = new ProjectExecutionLockService(jdbc, 30);
        service.release("run-1");
        verify(jdbc).update("DELETE FROM agent_project_write_lock WHERE run_id = ?", "run-1");
    }
}
