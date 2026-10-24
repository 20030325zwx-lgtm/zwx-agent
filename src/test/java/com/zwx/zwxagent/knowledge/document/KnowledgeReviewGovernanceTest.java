package com.zwx.zwxagent.knowledge.document;

import com.zwx.zwxagent.platform.security.AppUser;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.security.ReviewAccessGuard;
import com.zwx.zwxagent.platform.security.Role;
import com.zwx.zwxagent.platform.security.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeReviewGovernanceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AgentReviewerAssignmentService assignmentService =
            new AgentReviewerAssignmentService(jdbcTemplate, userRepository);
    private final ReviewAccessGuard guard = new ReviewAccessGuard(assignmentService);

    private CurrentActor actor(Role role) {
        return new CurrentActor("user-1", "default", "reviewer1", role);
    }

    @Test
    void adminBypassesAssignmentCheck() {
        assertThat(assignmentService.isAssigned("default", "test", "user-1")).isFalse();
        guard.requireReviewAccess(actor(Role.ADMIN), "any-agent");
    }

    @Test
    void assignedReviewerPassesAndUnassignedReviewerIsDenied() {
        when(jdbcTemplate.queryForObject(contains("FROM agent_reviewer"), eq(Integer.class),
                any(), any(), any())).thenReturn(1);
        guard.requireReviewAccess(actor(Role.REVIEWER), "test");

        when(jdbcTemplate.queryForObject(contains("FROM agent_reviewer"), eq(Integer.class),
                any(), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> guard.requireReviewAccess(actor(Role.REVIEWER), "other-agent"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void userAndWorkflowRolesAreAlwaysDenied() {
        when(jdbcTemplate.queryForObject(contains("FROM agent_reviewer"), eq(Integer.class),
                any(), any(), any())).thenReturn(1);
        assertThatThrownBy(() -> guard.requireReviewAccess(actor(Role.USER), "test"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guard.requireReviewAccess(actor(Role.WORKFLOW), "test"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void assignPromotesUserToReviewerAndInsertsAssignment() {
        when(userRepository.findByUsername("smokeuser")).thenReturn(Optional.of(
                new AppUser("u-1", "default", "smokeuser", "hash", Role.USER, "ACTIVE")));

        AgentReviewerAssignmentService.ReviewerAssignment assignment =
                assignmentService.assign(actor(Role.ADMIN), "default", "test", "smokeuser");

        assertThat(assignment.userId()).isEqualTo("u-1");
        verify(userRepository).updateRole("u-1", Role.REVIEWER);
        verify(jdbcTemplate).update(contains("INSERT INTO agent_reviewer"), any(Object[].class));
    }

    @Test
    void assignRejectsWorkflowServiceAccounts() {
        when(userRepository.findByUsername("svc")).thenReturn(Optional.of(
                new AppUser("u-2", "default", "svc", "hash", Role.WORKFLOW, "ACTIVE")));
        assertThatThrownBy(() -> assignmentService.assign(actor(Role.ADMIN), "default", "test", "svc"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbcTemplate, never()).update(contains("INSERT INTO agent_reviewer"), any(Object[].class));
    }

    @Test
    void reviewLogRejectsUnknownActionsAndPersistsAllowedOnes() {
        AgentKnowledgeReviewLogService logService = new AgentKnowledgeReviewLogService(jdbcTemplate);
        assertThatThrownBy(() -> logService.record("default", "test", "doc-1", "DELETE_ALL", null, "SYSTEM", null, null))
                .isInstanceOf(IllegalArgumentException.class);

        logService.record("default", "test", "doc-1", "PUBLISH", actor(Role.REVIEWER), "API", null, null);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("INSERT INTO agent_knowledge_review_log"), args.capture());
        assertThat(args.getValue()[4]).isEqualTo("PUBLISH");
        assertThat(args.getValue()[6]).isEqualTo("reviewer1");
    }

    @Test
    void listByDocumentQueriesWithinScope() {
        doAnswer(invocation -> List.of()).when(jdbcTemplate).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                eq("default"), eq("test"), eq("doc-1"));
        assertThat(assignmentService.list("default", "test")).isNotNull();
    }
}
