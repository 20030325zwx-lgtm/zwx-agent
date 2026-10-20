package com.zwx.zwxagent.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.agent.graph.ActivityEvent;
import com.zwx.zwxagent.agent.graph.GraphCheckpoint;
import com.zwx.zwxagent.agent.graph.PlanStep;
import com.zwx.zwxagent.conversation.ApplicationInstanceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 超级智能体执行实例的持久化：run 元数据 + 图检查点 + 部分回答。
 * 检查点按节点写入 PostgreSQL，服务重启后可据此继续执行。
 */
@Slf4j
@Service
public class AgentRunService {

    private static final TypeReference<List<PlanStep>> PLAN_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ActivityEvent>> EVENT_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ApplicationInstanceRegistry instanceRegistry;

    public AgentRunService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this(jdbcTemplate, objectMapper, null);
    }

    @Autowired
    public AgentRunService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                           ApplicationInstanceRegistry instanceRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.instanceRegistry = instanceRegistry;
    }

    public String start(String conversationId, String tenantId, String userId, String agentKey,
                        long userMessageId, String input, String historyContext,
                        String knowledgeContext, String ragTraceJson) {
        String id = UUID.randomUUID().toString();
        String ownerInstance = instanceRegistry == null ? null : instanceRegistry.currentInstanceId();
        int inserted = jdbcTemplate.update("""
                INSERT INTO agent_run (id, conversation_id, tenant_id, user_id, agent_key, user_message_id,
                                       status, input, history_context, knowledge_context, rag_trace_json,
                                       owner_instance)
                SELECT ?, c.id, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?
                FROM agent_conversation c
                WHERE c.id = ? AND c.tenant_id = ? AND c.user_id = ? AND c.agent_key = ?
                """, id, tenantId, userId, agentKey, userMessageId, input, historyContext,
                knowledgeContext, ragTraceJson, ownerInstance, conversationId, tenantId, userId, agentKey);
        if (inserted == 0) throw new IllegalArgumentException("Agent conversation was not found");
        return id;
    }

    public void checkpoint(String runId, GraphCheckpoint checkpoint) {
        if (runId == null || runId.isBlank() || checkpoint == null) return;
        log.debug("persist agent run checkpoint; runId={}, lastNode={}, nextNode={}", runId, checkpoint.lastNode(), checkpoint.nextNode());
        jdbcTemplate.update("""
                UPDATE agent_run SET
                    last_node = ?, next_node = ?, plan = CAST(? AS jsonb),
                    task_results = CAST(? AS jsonb), activities = CAST(? AS jsonb), sse_events = CAST(? AS jsonb),
                    revision = ?, verification_status = ?, verification_feedback = ?,
                    final_answer = ?, partial_answer = CASE WHEN ? = '' THEN partial_answer ELSE ? END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                """, checkpoint.lastNode(), checkpoint.nextNode(), json(checkpoint.plan()),
                json(checkpoint.taskResults()), json(checkpoint.activities()), json(checkpoint.events()),
                checkpoint.revision(), checkpoint.verificationStatus(), checkpoint.verificationFeedback(),
                checkpoint.finalAnswer(), nullToEmpty(checkpoint.partialAnswer()), nullToEmpty(checkpoint.partialAnswer()),
                runId);
    }

    public void updatePartialAnswer(String runId, String partialAnswer) {
        if (runId == null || runId.isBlank() || partialAnswer == null || partialAnswer.isBlank()) return;
        jdbcTemplate.update("""
                UPDATE agent_run SET partial_answer = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                """, partialAnswer, runId);
    }

    public void finish(String runId, String status, String answer, String error, Long assistantMessageId) {
        jdbcTemplate.update("""
                UPDATE agent_run SET status = ?, final_answer = COALESCE(?, final_answer),
                    partial_answer = COALESCE(?, partial_answer), error_message = ?,
                    assistant_message_id = COALESCE(?, assistant_message_id),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, status, answer, answer, error, assistantMessageId, runId);
    }

    public void restart(String runId) {
        String ownerInstance = instanceRegistry == null ? null : instanceRegistry.currentInstanceId();
        jdbcTemplate.update("""
                UPDATE agent_run SET status = 'RUNNING', error_message = NULL, owner_instance = ?,
                    resume_count = resume_count + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, ownerInstance, runId);
    }

    public void markInterrupted(String runId, String reason) {
        jdbcTemplate.update("""
                UPDATE agent_run SET status = 'INTERRUPTED', error_message = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                """, reason, runId);
    }

    public void bindAssistantMessage(String runId, long assistantMessageId) {
        jdbcTemplate.update("UPDATE agent_run SET assistant_message_id = ? WHERE id = ?", assistantMessageId, runId);
    }

    public Optional<AgentRun> findScoped(String tenantId, String userId, String conversationId, String runId) {
        return jdbcTemplate.query("""
                SELECT * FROM agent_run
                WHERE id = ? AND tenant_id = ? AND user_id = ? AND conversation_id = ? AND agent_key = 'super'
                """, runMapper(), runId, tenantId, userId, conversationId).stream().findFirst();
    }

    public Optional<AgentRun> findLatestResumable(String tenantId, String userId, String conversationId) {
        return jdbcTemplate.query("""
                SELECT * FROM agent_run
                WHERE tenant_id = ? AND user_id = ? AND conversation_id = ? AND agent_key = 'super'
                  AND status IN ('INTERRUPTED', 'FAILED')
                ORDER BY updated_at DESC LIMIT 1
                """, runMapper(), tenantId, userId, conversationId).stream().findFirst();
    }

    public List<AgentRun> findOrphanedRunning() {
        return jdbcTemplate.query("""
                SELECT r.* FROM agent_run r
                WHERE r.status = 'RUNNING'
                  AND (
                      (r.owner_instance IS NOT NULL AND NOT EXISTS (
                          SELECT 1 FROM agent_instance i
                          WHERE i.instance_id = r.owner_instance
                            AND i.heartbeat_at > CURRENT_TIMESTAMP - INTERVAL '15 seconds'
                      ))
                      OR (r.owner_instance IS NULL AND NOT EXISTS (
                          SELECT 1 FROM agent_conversation_lock l
                          WHERE l.conversation_id = r.conversation_id AND l.expires_at > CURRENT_TIMESTAMP
                      ))
                  )
                ORDER BY r.created_at
                """, runMapper());
    }

    private RowMapper<AgentRun> runMapper() {
        return (rs, rowNum) -> readRun(rs);
    }

    private AgentRun readRun(ResultSet rs) throws SQLException {
        GraphCheckpoint checkpoint = new GraphCheckpoint(
                rs.getString("last_node"),
                rs.getString("next_node"),
                read(rs.getString("plan"), PLAN_TYPE),
                read(rs.getString("task_results"), STRING_LIST_TYPE),
                read(rs.getString("activities"), STRING_LIST_TYPE),
                read(rs.getString("sse_events"), EVENT_LIST_TYPE),
                rs.getInt("revision"),
                rs.getString("verification_status"),
                rs.getString("verification_feedback"),
                rs.getString("final_answer"),
                rs.getString("partial_answer"));
        long assistantMessageId = rs.getLong("assistant_message_id");
        Long assistantId = rs.wasNull() ? null : assistantMessageId;
        return new AgentRun(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("agent_key"),
                rs.getLong("user_message_id"),
                assistantId,
                rs.getString("status"),
                rs.getString("input"),
                rs.getString("history_context"),
                rs.getString("knowledge_context"),
                rs.getString("rag_trace_json"),
                checkpoint,
                rs.getString("error_message"),
                rs.getInt("resume_count"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private <T> List<T> read(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read persisted agent run checkpoint", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize agent run checkpoint", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
