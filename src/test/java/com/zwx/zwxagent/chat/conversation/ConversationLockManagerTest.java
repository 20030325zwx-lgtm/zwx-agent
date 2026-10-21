package com.zwx.zwxagent.chat.conversation;

import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ConversationLockManagerTest {

    @Test
    void acquiredLeaseCanBeClosedRepeatedlyAndOnlyReleasesItsOwnerToken() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doAnswer(invocation -> invocation.getArgument(3))
                .when(jdbcTemplate).queryForObject(anyString(), eq(String.class), any(Object[].class));
        ConversationLockManager manager = new ConversationLockManager(jdbcTemplate, 60);

        ConversationLockManager.Lease lease = manager.tryAcquire("tenant:user:agent:conversation");
        assertNotNull(lease);

        lease.close();
        lease.close();

        verify(jdbcTemplate, times(1)).update(eq(
                "DELETE FROM agent_conversation_lock WHERE conversation_id = ? AND owner_token = ?"),
                eq("tenant:user:agent:conversation"), anyString());
    }

    @Test
    void activeLeaseReturnsBusyWhenDatabaseReportsNoReturnedRow() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doThrow(new EmptyResultDataAccessException(1))
                .when(jdbcTemplate).queryForObject(anyString(), eq(String.class), any(Object[].class));
        ConversationLockManager manager = new ConversationLockManager(jdbcTemplate, 60);

        assertNull(manager.tryAcquire("conversation"));
    }
}
