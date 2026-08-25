package com.zwx.zwxagent.skills;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class SkillConfigurationService {
    private final JdbcTemplate jdbcTemplate;

    public SkillConfigurationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isEnabled(String tenantId, String agentKey, String skillId) {
        Boolean enabled = jdbcTemplate.query(
                "SELECT enabled FROM agent_skill_configuration WHERE tenant_id = ? AND agent_key = ? AND skill_id = ?",
                resultSet -> resultSet.next() ? resultSet.getBoolean("enabled") : null,
                tenantId, agentKey, skillId);
        return enabled == null || enabled;
    }

    @Transactional
    public void save(String tenantId, String agentKey, Set<String> skillIds) {
        jdbcTemplate.update("DELETE FROM agent_skill_configuration WHERE tenant_id = ? AND agent_key = ?", tenantId, agentKey);
        jdbcTemplate.batchUpdate(
                "INSERT INTO agent_skill_configuration (tenant_id, agent_key, skill_id, enabled) VALUES (?, ?, ?, ?)",
                Set.of("web-research").stream().map(skillId -> new Object[]{tenantId, agentKey, skillId, skillIds.contains(skillId)}).toList());
    }
}
