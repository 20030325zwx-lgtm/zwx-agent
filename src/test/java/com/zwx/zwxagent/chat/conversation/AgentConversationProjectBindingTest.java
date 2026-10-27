package com.zwx.zwxagent.chat.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.platform.workspace.ProjectWorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentConversationProjectBindingTest {

    @Test
    void rejectsProjectOwnedByAnotherTenantOrUser() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ProjectWorkspaceService workspaceService = mock(ProjectWorkspaceService.class);
        when(workspaceService.isOwned("tenant-a", "user-a", "project-b")).thenReturn(false);
        AgentConversationService service = new AgentConversationService(jdbcTemplate, new ObjectMapper(), workspaceService);

        assertThrows(AccessDeniedException.class, () -> service.createConversation(
                "tenant-a", "user-a", "super", "新的对话", "project-b"));
    }
}
