package com.zwx.zwxagent.knowledge.document;

import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 知识文档审批审计：只追加，不提供更新与删除路径。
 * action: SUBMIT / PUBLISH / REJECT / TIMEOUT / RESUME_FAIL
 */
@Service
public class AgentKnowledgeReviewLogService {

    public static final Set<String> ALLOWED_ACTIONS = Set.of("SUBMIT", "PUBLISH", "REJECT", "TIMEOUT", "RESUME_FAIL");

    public record ReviewLogEntry(String id, String tenantId, String agentKey, String documentId, String action,
                                 String actorUserId, String actorUsername, String actorRole, String channel,
                                 String note, String n8nExecutionId, Instant createdAt) {
    }

    private final JdbcTemplate jdbcTemplate;

    public AgentKnowledgeReviewLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String tenantId, String agentKey, String documentId, String action,
                       CurrentActor actor, String channel, String note, String n8nExecutionId) {
        if (!ALLOWED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Unsupported review action: " + action);
        }
        jdbcTemplate.update("""
                INSERT INTO agent_knowledge_review_log
                    (id, tenant_id, agent_key, document_id, action,
                     actor_user_id, actor_username, actor_role, channel, note, n8n_execution_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), tenantId, agentKey, documentId, action,
                actor == null ? null : actor.userId(),
                actor == null ? null : actor.username(),
                actor == null ? null : actor.role().name(),
                channel, note, n8nExecutionId);
    }

    public List<ReviewLogEntry> listByDocument(String tenantId, String agentKey, String documentId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, agent_key, document_id, action,
                       actor_user_id, actor_username, actor_role, channel, note, n8n_execution_id, created_at
                FROM agent_knowledge_review_log
                WHERE tenant_id = ? AND agent_key = ? AND document_id = ?
                ORDER BY created_at
                """, (rs, rowNum) -> new ReviewLogEntry(
                rs.getString("id"), rs.getString("tenant_id"), rs.getString("agent_key"),
                rs.getString("document_id"), rs.getString("action"),
                rs.getString("actor_user_id"), rs.getString("actor_username"), rs.getString("actor_role"),
                rs.getString("channel"), rs.getString("note"), rs.getString("n8n_execution_id"),
                rs.getTimestamp("created_at").toInstant()), tenantId, agentKey, documentId);
    }
}
