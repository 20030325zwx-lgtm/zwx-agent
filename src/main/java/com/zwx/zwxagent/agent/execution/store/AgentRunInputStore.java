package com.zwx.zwxagent.agent.execution.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * agent_run_input 读写（整改 A1，方案 §8.1/§5.3）：
 * 问题/答复/纠偏/下一轮/计划批准统一入队；答复与原问题关联且只消费一次；
 * client_request_key 幂等（同请求重复提交返回既有行），版本锁防止覆盖已被认领的输入。
 */
@Slf4j
@Service
public class AgentRunInputStore {

    public static final String KIND_QUESTION = "QUESTION";
    public static final String KIND_ANSWER = "ANSWER";
    public static final String KIND_STEER = "STEER";
    public static final String KIND_NEXT_TURN = "NEXT_TURN";
    public static final String KIND_PLAN_APPROVAL = "PLAN_APPROVAL";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentRunInputStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record InputRecord(UUID id, String conversationId, String runId, UUID stepId, UUID parentInputId,
                              long inputSeq, String kind, String payloadJson, String clientRequestKey,
                              String status, int version, Instant createdAt) {
    }

    /**
     * 登记一条输入（幂等）：同 (tenant, user, client_request_key) 重复提交返回既有行而不新建。
     */
    @Transactional
    public InputRecord record(String tenantId, String userId, String conversationId, String runId, UUID stepId,
                              String kind, Map<String, Object> payload, String clientRequestKey,
                              UUID parentInputId, Instant answerDeadline) {
        if (clientRequestKey == null || clientRequestKey.isBlank()) {
            throw new IllegalArgumentException("client_request_key 必填");
        }
        Optional<InputRecord> existing = findByRequestKey(tenantId, userId, clientRequestKey);
        if (existing.isPresent()) return existing.get();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_run_input (id, tenant_id, user_id, conversation_id, run_id, step_id,
                    parent_input_id, kind, payload, client_request_key, status, answer_deadline)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, id, tenantId, userId, conversationId, runId, stepId, parentInputId, kind,
                writeJson(payload == null ? Map.of() : payload), clientRequestKey, STATUS_PENDING, answerDeadline);
        return findByRequestKey(tenantId, userId, clientRequestKey).orElseThrow();
    }

    /**
     * 消费一条 PENDING 输入（答复/纠偏进入执行）：CAS PENDING→ACCEPTED，只允许消费一次。
     *
     * @return false 表示已被消费/取消或版本冲突
     */
    public boolean accept(String tenantId, String userId, UUID inputId, int expectedVersion) {
        int updated = jdbc.update("""
                UPDATE agent_run_input
                SET status = ?, accepted_at = CURRENT_TIMESTAMP, version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND user_id = ? AND status = ? AND version = ?
                """, STATUS_ACCEPTED, inputId, tenantId, userId, STATUS_PENDING, expectedVersion);
        return updated == 1;
    }

    /** 执行器实际应用该输入后调用；状态机要求先 ACCEPTED 再 APPLIED。 */
    public boolean markApplied(String tenantId, String userId, UUID inputId, String resultRunId) {
        int updated = jdbc.update("""
                UPDATE agent_run_input
                SET status = ?, applied_at = CURRENT_TIMESTAMP, result_run_id = COALESCE(?, result_run_id),
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND user_id = ? AND status = ?
                """, STATUS_APPLIED, resultRunId, inputId, tenantId, userId, STATUS_ACCEPTED);
        return updated == 1;
    }

    /** 取消一条 PENDING 输入（撤回排队中的纠偏/答复）。 */
    public boolean cancel(String tenantId, String userId, UUID inputId, int expectedVersion) {
        int updated = jdbc.update("""
                UPDATE agent_run_input
                SET status = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND user_id = ? AND status = ? AND version = ?
                """, STATUS_CANCELLED, inputId, tenantId, userId, STATUS_PENDING, expectedVersion);
        return updated == 1;
    }

    public Optional<InputRecord> findByRequestKey(String tenantId, String userId, String clientRequestKey) {
        List<InputRecord> rows = jdbc.query("""
                SELECT id, conversation_id, run_id, step_id, parent_input_id, input_seq, kind,
                       payload::text, client_request_key, status, version, created_at
                FROM agent_run_input
                WHERE tenant_id = ? AND user_id = ? AND client_request_key = ?
                """, (rs, index) -> new InputRecord(rs.getObject("id", UUID.class), rs.getString("conversation_id"),
                rs.getString("run_id"), rs.getObject("step_id", UUID.class), rs.getObject("parent_input_id", UUID.class),
                rs.getLong("input_seq"), rs.getString("kind"), rs.getString("payload"),
                rs.getString("client_request_key"), rs.getString("status"), rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant()),
                tenantId, userId, clientRequestKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<InputRecord> pendingOfRun(String tenantId, String userId, String runId) {
        return jdbc.query("""
                SELECT id, conversation_id, run_id, step_id, parent_input_id, input_seq, kind,
                       payload::text, client_request_key, status, version, created_at
                FROM agent_run_input
                WHERE tenant_id = ? AND user_id = ? AND run_id = ? AND status = ?
                ORDER BY input_seq
                """, (rs, index) -> new InputRecord(rs.getObject("id", UUID.class), rs.getString("conversation_id"),
                rs.getString("run_id"), rs.getObject("step_id", UUID.class), rs.getObject("parent_input_id", UUID.class),
                rs.getLong("input_seq"), rs.getString("kind"), rs.getString("payload"),
                rs.getString("client_request_key"), rs.getString("status"), rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant()),
                tenantId, userId, runId, STATUS_PENDING);
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception cause) {
            throw new IllegalArgumentException("输入 payload 序列化失败", cause);
        }
    }
}
