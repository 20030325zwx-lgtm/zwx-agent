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
 * agent_run_checkpoint 读写（整改 A1，方案 §8.1）：
 * 新引擎恢复检查点的权威存储。checkpoint_no 在锁 run 行后取 MAX+1，避免并发打点重复；
 * content_hash 保存 state_json 的 SHA-256，供恢复时完整性校验。
 * 旧 agent_run 的图检查点保持不动（旧引擎兼容缓存），两边不互相编辑。
 */
@Slf4j
@Service
public class AgentRunCheckpointStore {

    public static final String REASON_WAITING = "WAITING";
    public static final String REASON_BEFORE_EFFECT = "BEFORE_EFFECT";
    public static final String REASON_AFTER_STEP = "AFTER_STEP";
    public static final String REASON_COMPRESSION = "COMPRESSION";
    public static final String REASON_PAUSE = "PAUSE";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentRunCheckpointStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CheckpointRow(UUID id, String runId, UUID stepId, long checkpointNo, int schemaVersion,
                                int planRevision, String stateJson, String summaryJson, String contentHash,
                                long lastEventSeq, UUID lastConsumedInputId, String reason, Instant createdAt) {
    }

    /**
     * 追加一个检查点（同事务锁 run 行分配 checkpoint_no）。
     *
     * @return 新检查点 id；run 不存在或归属不符时抛 IllegalStateException
     */
    @Transactional
    public UUID append(String runId, String tenantId, String userId, UUID stepId, int schemaVersion,
                       int planRevision, Map<String, Object> state, Map<String, Object> summary,
                       long lastEventSeq, UUID lastConsumedInputId, String reason) {
        String stateJson = writeJson(state == null ? Map.of() : state);
        String summaryJson = writeJson(summary == null ? Map.of() : summary);
        Long nextNo = jdbc.queryForObject("""
                SELECT COALESCE(MAX(c.checkpoint_no), 0) + 1
                FROM agent_run_checkpoint c
                WHERE c.run_id = ? AND c.tenant_id = ? AND c.user_id = ?
                """, Long.class, runId, tenantId, userId);
        UUID id = UUID.randomUUID();
        int updated = jdbc.update("""
                INSERT INTO agent_run_checkpoint (id, tenant_id, user_id, run_id, step_id, checkpoint_no,
                    schema_version, plan_revision, state_json, summary_json, content_hash,
                    last_event_seq, last_consumed_input_id, reason)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?
                FROM agent_run r
                WHERE r.id = ? AND r.tenant_id = ? AND r.user_id = ?
                """, id, tenantId, userId, runId, stepId, nextNo, schemaVersion, planRevision,
                stateJson, summaryJson, sha256Hex(stateJson), lastEventSeq, lastConsumedInputId, reason,
                runId, tenantId, userId);
        if (updated != 1) throw new IllegalStateException("检查点写入失败：run 不存在或归属不符");
        return id;
    }

    public Optional<CheckpointRow> latest(String runId, String tenantId, String userId) {
        List<CheckpointRow> rows = jdbc.query("""
                SELECT id, run_id, step_id, checkpoint_no, schema_version, plan_revision,
                       state_json::text, summary_json::text, content_hash, last_event_seq,
                       last_consumed_input_id, reason, created_at
                FROM agent_run_checkpoint
                WHERE run_id = ? AND tenant_id = ? AND user_id = ?
                ORDER BY checkpoint_no DESC LIMIT 1
                """, (rs, index) -> new CheckpointRow(rs.getObject("id", UUID.class), rs.getString("run_id"),
                rs.getObject("step_id", UUID.class), rs.getLong("checkpoint_no"), rs.getInt("schema_version"),
                rs.getInt("plan_revision"), rs.getString("state_json"), rs.getString("summary_json"),
                rs.getString("content_hash"), rs.getLong("last_event_seq"),
                rs.getObject("last_consumed_input_id", UUID.class), rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()),
                runId, tenantId, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception cause) {
            throw new IllegalArgumentException("检查点 JSON 序列化失败", cause);
        }
    }

    static String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte item : hash) hex.append(Character.forDigit((item >> 4) & 0xF, 16))
                    .append(Character.forDigit(item & 0xF, 16));
            return hex.toString();
        } catch (Exception cause) {
            throw new IllegalStateException("SHA-256 unavailable", cause);
        }
    }
}
