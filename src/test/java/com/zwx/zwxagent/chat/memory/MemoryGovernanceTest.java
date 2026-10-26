package com.zwx.zwxagent.chat.memory;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryGovernanceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MemoryService service = new MemoryService(jdbcTemplate, null, false);

    @Test
    @SuppressWarnings("unchecked")
    void listFactsScopesToTenantAndUser() {
        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<MemoryService.MemoryFactView>>any(),
                eq("t-1"), eq("u-1"), eq("super"), eq("super"), eq(100)))
                .thenReturn(List.of(new MemoryService.MemoryFactView(1L, "super", "偏好简洁回答", "conv-1", null, null)));
        List<MemoryService.MemoryFactView> facts = service.listFacts("t-1", "u-1", "super", 100);
        Assertions.assertEquals(1, facts.size());
        Assertions.assertEquals("偏好简洁回答", facts.get(0).fact());
        Assertions.assertTrue(service.listFacts("", "u-1", "super", 100).isEmpty());
        Assertions.assertTrue(service.listFacts("t-1", "", "super", 100).isEmpty());
    }

    @Test
    void updateFactRecomputesAndScopes() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
        Assertions.assertTrue(service.updateFact("t-1", "u-1", 7L, "  新的事实  "));
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(0);
        Assertions.assertFalse(service.updateFact("t-1", "u-1", 7L, "别人的事实"));
        Assertions.assertFalse(service.updateFact("t-1", "u-1", 7L, "   "));
    }

    @Test
    void updateFactHashConflictReturnsFalse() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("uk_agent_memory_fact"));
        Assertions.assertFalse(service.updateFact("t-1", "u-1", 7L, "与既有事实重复"));
    }

    @Test
    void deleteFactScopesToOwner() {
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(1);
        Assertions.assertTrue(service.deleteFact("t-1", "u-1", 7L));
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(0);
        Assertions.assertFalse(service.deleteFact("t-1", "u-1", 7L));
    }

    @Test
    void addFactNormalizesBeforeHashing() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
        Assertions.assertDoesNotThrow(() -> service.addFact("t-1", "u-1", "super", "用户偏好中文回答"));
    }
}
