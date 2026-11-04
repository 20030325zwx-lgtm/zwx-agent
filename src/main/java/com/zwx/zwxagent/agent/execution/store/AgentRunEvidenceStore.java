package com.zwx.zwxagent.agent.execution.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * agent_run_evidence 读写（整改 A1，方案 §8.1/§5.4）：
 * 验收证据（文件/退出码/测试报告/浏览器断言/媒体产物）落库；verification_status 独立更新，
 * 未验证（NOT_RUN）与验证失败（FAIL）分别呈现；producer 区分 Agent 产物与用户上传。
 */
@Slf4j
@Service
public class AgentRunEvidenceStore {

    public static final String KIND_FILE = "FILE";
    public static final String KIND_COMMAND_EXIT = "COMMAND_EXIT";
    public static final String KIND_TEST_REPORT = "TEST_REPORT";
    public static final String KIND_BROWSER_ASSERTION = "BROWSER_ASSERTION";
    public static final String KIND_MEDIA_OUTPUT = "MEDIA_OUTPUT";
    public static final String KIND_TEXT = "TEXT";

    public static final String PRODUCER_AGENT = "AGENT";
    public static final String PRODUCER_USER_UPLOAD = "USER_UPLOAD";
    public static final String PRODUCER_SYSTEM = "SYSTEM";

    public static final String VERIFICATION_NOT_RUN = "NOT_RUN";
    public static final String VERIFICATION_PASS = "PASS";
    public static final String VERIFICATION_FAIL = "FAIL";
    public static final String VERIFICATION_INCONCLUSIVE = "INCONCLUSIVE";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentRunEvidenceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record EvidenceRow(UUID id, String runId, UUID stepId, Long toolCallId, String kind, String producer,
                              String artifactRef, String contentHash, String mediaType, Long sizeBytes,
                              String resultJson, String verificationStatus, Instant verifiedAt,
                              String criterionKey, Instant createdAt) {
    }

    public UUID record(String runId, String tenantId, String userId, UUID stepId, Long toolCallId,
                       String kind, String producer, String artifactRef, String contentHash,
                       String mediaType, Long sizeBytes, Map<String, Object> result, String criterionKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_run_evidence (id, tenant_id, user_id, run_id, step_id, tool_call_id,
                    kind, producer, artifact_ref, content_hash, media_type, size_bytes, result_json, criterion_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, id, tenantId, userId, runId, stepId, toolCallId, kind, producer, artifactRef,
                contentHash, mediaType, sizeBytes, writeJson(result == null ? Map.of() : result), criterionKey);
        return id;
    }

    /** 更新验证结论；不允许把 NOT_RUN 冒充 PASS，状态回写必须显式。 */
    public boolean updateVerification(String runId, String tenantId, String userId, UUID evidenceId,
                                      String verificationStatus, String criterionKey) {
        if (!List.of(VERIFICATION_PASS, VERIFICATION_FAIL, VERIFICATION_INCONCLUSIVE, VERIFICATION_NOT_RUN)
                .contains(verificationStatus)) {
            throw new IllegalArgumentException("非法的验证状态：" + verificationStatus);
        }
        int updated = jdbc.update("""
                UPDATE agent_run_evidence
                SET verification_status = ?, criterion_key = COALESCE(?, criterion_key),
                    verified_at = CASE WHEN ? = 'NOT_RUN' THEN NULL ELSE CURRENT_TIMESTAMP END
                WHERE id = ? AND run_id = ? AND tenant_id = ? AND user_id = ?
                """, verificationStatus, criterionKey, verificationStatus, evidenceId, runId, tenantId, userId);
        return updated == 1;
    }

    public List<EvidenceRow> listByRun(String runId, String tenantId, String userId, String verificationStatus) {
        return jdbc.query("""
                SELECT id, run_id, step_id, tool_call_id, kind, producer, artifact_ref, content_hash,
                       media_type, size_bytes, result_json::text, verification_status, verified_at,
                       criterion_key, created_at
                FROM agent_run_evidence
                WHERE run_id = ? AND tenant_id = ? AND user_id = ?
                  AND (?::text IS NULL OR verification_status = ?)
                ORDER BY created_at, id
                """, (rs, index) -> new EvidenceRow(rs.getObject("id", UUID.class), rs.getString("run_id"),
                rs.getObject("step_id", UUID.class), rs.getObject("tool_call_id", Long.class), rs.getString("kind"),
                rs.getString("producer"), rs.getString("artifact_ref"), rs.getString("content_hash"),
                rs.getString("media_type"), rs.getObject("size_bytes", Long.class), rs.getString("result_json"),
                rs.getString("verification_status"),
                rs.getTimestamp("verified_at") == null ? null : rs.getTimestamp("verified_at").toInstant(),
                rs.getString("criterion_key"),
                rs.getTimestamp("created_at").toInstant()),
                runId, tenantId, userId, verificationStatus, verificationStatus);
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception cause) {
            throw new IllegalArgumentException("证据 result 序列化失败", cause);
        }
    }
}
