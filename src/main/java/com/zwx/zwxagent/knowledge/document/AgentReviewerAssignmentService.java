package com.zwx.zwxagent.knowledge.document;

import com.zwx.zwxagent.platform.security.AppUser;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.security.Role;
import com.zwx.zwxagent.platform.security.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 审批人分配：角色（REVIEWER）决定"能否成为审批人"，本表决定"可审批的 (tenant, agent) 范围"。
 * 设计：docs/design/2026-10-24-knowledge-approval-entry.md
 */
@Service
public class AgentReviewerAssignmentService {

    public record ReviewerAssignment(String id, String tenantId, String agentKey, String userId, String username,
                                     String assignedBy, Instant createdAt) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;

    public AgentReviewerAssignmentService(JdbcTemplate jdbcTemplate, UserRepository userRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
    }

    public boolean isAssigned(String tenantId, String agentKey, String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_reviewer WHERE tenant_id = ? AND agent_key = ? AND user_id = ?",
                Integer.class, tenantId, agentKey, userId);
        return count != null && count > 0;
    }

    /**
     * 管理员分配审批人；普通用户分配时自动升级为 REVIEWER，工作流服务账号不允许成为审批人。
     */
    public ReviewerAssignment assign(CurrentActor admin, String tenantId, String agentKey, String username) {
        AppUser user = userRepository.findByUsername(username == null ? "" : username.trim())
                .orElseThrow(() -> new IllegalArgumentException("User does not exist: " + username));
        if (user.role() == Role.WORKFLOW) {
            throw new IllegalArgumentException("Workflow service accounts cannot be reviewers");
        }
        if (user.role() == Role.USER) {
            userRepository.updateRole(user.id(), Role.REVIEWER);
        }
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO agent_reviewer (id, tenant_id, agent_key, user_id, assigned_by)
                VALUES (?, ?, ?, ?, ?)
                """, id, tenantId, agentKey, user.id(), admin.userId());
        return new ReviewerAssignment(id, tenantId, agentKey, user.id(), user.username(), admin.userId(), Instant.now());
    }

    public void unassign(String id) {
        jdbcTemplate.update("DELETE FROM agent_reviewer WHERE id = ?", id);
    }

    public List<ReviewerAssignment> list(String tenantId, String agentKey) {
        return jdbcTemplate.query("""
                SELECT r.id, r.tenant_id, r.agent_key, r.user_id, u.username, r.assigned_by, r.created_at
                FROM agent_reviewer r JOIN app_user u ON u.id = r.user_id
                WHERE r.tenant_id = ? AND (?::text IS NULL OR r.agent_key = ?)
                ORDER BY r.created_at
                """, (rs, rowNum) -> new ReviewerAssignment(
                rs.getString("id"), rs.getString("tenant_id"), rs.getString("agent_key"),
                rs.getString("user_id"), rs.getString("username"), rs.getString("assigned_by"),
                rs.getTimestamp("created_at").toInstant()), tenantId, agentKey, agentKey);
    }
}
