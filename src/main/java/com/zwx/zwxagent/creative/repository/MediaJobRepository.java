package com.zwx.zwxagent.creative.repository;

import com.zwx.zwxagent.creative.domain.AttemptOutcome;
import com.zwx.zwxagent.creative.domain.IngestState;
import com.zwx.zwxagent.creative.domain.InputSnapshot;
import com.zwx.zwxagent.creative.domain.JobSourceType;
import com.zwx.zwxagent.creative.domain.MediaJobRecord;
import com.zwx.zwxagent.creative.domain.MediaJobStatus;
import com.zwx.zwxagent.creative.domain.ModelSnapshot;
import com.zwx.zwxagent.creative.domain.StorageBackend;
import com.zwx.zwxagent.creative.support.CreativeJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * media_job 数据访问。状态迁移必须先 SELECT ... FOR UPDATE 锁行（实施手册 §2.2 事件序号规则）；
 * 带 version/lease_epoch 的条件更新用于 fencing（A11）。
 */
@Repository
public class MediaJobRepository {

    private final JdbcTemplate jdbcTemplate;

    public MediaJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<MediaJobRecord> MAPPER = MediaJobRepository::map;

    private static final String SELECT = """
            SELECT id, project_id, tenant_id, user_id, source_type, source_run_id, source_node_id,
                   parent_job_id, operation, status, idempotency_key, request_hash, input_snapshot,
                   model_snapshot, provider_id, provider_task_id, submission_key, version, lease_owner,
                   lease_until, lease_epoch, inflight_reserved, next_action_at, blocked_reason,
                   attention_required, error_code, error_summary, cancel_requested_at,
                   observation_deadline_at, reconcile_after, manual_resolution,
                   created_at, updated_at, finished_at
            FROM media_job
            """;

    public void insert(MediaJobRecord job) {
        jdbcTemplate.update("""
                        INSERT INTO media_job
                            (id, project_id, tenant_id, user_id, source_type, source_run_id, source_node_id,
                             parent_job_id, operation, status, idempotency_key, request_hash, input_snapshot,
                             model_snapshot, next_action_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                        """,
                ps -> {
                    ps.setObject(1, job.id());
                    ps.setObject(2, job.projectId());
                    ps.setString(3, job.tenantId());
                    ps.setString(4, job.userId());
                    ps.setString(5, job.sourceType().name());
                    ps.setString(6, job.sourceRunId());
                    ps.setString(7, job.sourceNodeId());
                    ps.setObject(8, job.parentJobId());
                    ps.setString(9, job.operation());
                    ps.setString(10, job.status().name());
                    ps.setString(11, job.idempotencyKey());
                    ps.setString(12, job.requestHash());
                    ps.setString(13, CreativeJson.toJson(job.inputSnapshot()));
                    ps.setString(14, CreativeJson.toJson(job.modelSnapshot()));
                    ps.setTimestamp(15, job.nextActionAt() == null ? null : Timestamp.from(job.nextActionAt()));
                });
    }

    public Optional<MediaJobRecord> findById(UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<MediaJobRecord> findByIdForOwner(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ? AND tenant_id = ? AND user_id = ?",
                MAPPER, id, tenantId, userId).stream().findFirst();
    }

    public Optional<MediaJobRecord> findByIdempotencyKey(String tenantId, String userId, String idempotencyKey) {
        return jdbcTemplate.query(SELECT + " WHERE tenant_id = ? AND user_id = ? AND idempotency_key = ?",
                MAPPER, tenantId, userId, idempotencyKey).stream().findFirst();
    }

    /** 事务内锁行（事件与状态同事务写入的前置条件）。 */
    public Optional<MediaJobRecord> lockById(UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ? FOR UPDATE", MAPPER, id).stream().findFirst();
    }

    /**
     * 受版本与租约 epoch 保护的状态更新（fencing）。返回是否命中；
     * 调用方负责先用 canTransitionTo 校验合法迁移，并追加 error/时间等字段更新。
     */
    public boolean updateStatusFenced(UUID id, long expectedVersion, long expectedLeaseEpoch,
                                      MediaJobStatus target, Instant nextActionAt, Instant finishedAt) {
        return updateStatusFenced(id, expectedVersion, expectedLeaseEpoch, target, nextActionAt,
                finishedAt, null);
    }

    /** 状态迁移并可同时写入/清除 blocked_reason（如上游不支持取消时说明原因）。 */
    public boolean updateStatusFenced(UUID id, long expectedVersion, long expectedLeaseEpoch,
                                      MediaJobStatus target, Instant nextActionAt, Instant finishedAt,
                                      String blockedReason) {
        return jdbcTemplate.update("""
                        UPDATE media_job
                        SET status = ?, version = version + 1, next_action_at = ?, finished_at = ?,
                            blocked_reason = ?, updated_at = now()
                        WHERE id = ? AND version = ? AND lease_epoch = ?
                        """,
                ps -> {
                    ps.setString(1, target.name());
                    ps.setTimestamp(2, Timestamp.from(nextActionAt == null ? Instant.now() : nextActionAt));
                    ps.setTimestamp(3, finishedAt == null ? null : Timestamp.from(finishedAt));
                    ps.setString(4, blockedReason);
                    ps.setObject(5, id);
                    ps.setLong(6, expectedVersion);
                    ps.setLong(7, expectedLeaseEpoch);
                }) > 0;
    }

    /** 提交成功后写入上游任务标识（同一受租约保护的事务内调用）。 */
    public boolean attachProviderTask(UUID id, long expectedVersion, long expectedLeaseEpoch,
                                      long providerId, String providerTaskId, String submissionKey) {
        return jdbcTemplate.update("""
                        UPDATE media_job
                        SET provider_id = ?, provider_task_id = ?, submission_key = ?,
                            version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ? AND lease_epoch = ?
                        """,
                ps -> {
                    ps.setLong(1, providerId);
                    ps.setString(2, providerTaskId);
                    ps.setString(3, submissionKey);
                    ps.setObject(4, id);
                    ps.setLong(5, expectedVersion);
                    ps.setLong(6, expectedLeaseEpoch);
                }) > 0;
    }

    /** QUEUED→SUBMITTING：占用在途槽位并写入提交键（fencing）。 */
    public boolean markSubmittingFenced(UUID id, long expectedVersion, long expectedLeaseEpoch,
                                        String submissionKey, Instant leaseUntil) {
        return jdbcTemplate.update("""
                        UPDATE media_job
                        SET status = ?, submission_key = ?, inflight_reserved = TRUE, lease_until = ?,
                            version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ? AND lease_epoch = ?
                        """,
                ps -> {
                    ps.setString(1, MediaJobStatus.SUBMITTING.name());
                    ps.setString(2, submissionKey);
                    ps.setTimestamp(3, leaseUntil == null ? null : Timestamp.from(leaseUntil));
                    ps.setObject(4, id);
                    ps.setLong(5, expectedVersion);
                    ps.setLong(6, expectedLeaseEpoch);
                }) > 0;
    }

    /** 推迟下一次动作（配额不足/退避），不改变状态。 */
    public boolean pushNextActionFenced(UUID id, long expectedVersion, long expectedLeaseEpoch,
                                        Instant nextActionAt, boolean attentionRequired, String blockedReason) {
        return jdbcTemplate.update("""
                        UPDATE media_job
                        SET next_action_at = ?, attention_required = ?, blocked_reason = ?,
                            version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ? AND lease_epoch = ?
                        """,
                ps -> {
                    ps.setTimestamp(1, nextActionAt == null ? null : Timestamp.from(nextActionAt));
                    ps.setBoolean(2, attentionRequired);
                    if (blockedReason == null) ps.setNull(3, java.sql.Types.VARCHAR);
                    else ps.setString(3, blockedReason);
                    ps.setObject(4, id);
                    ps.setLong(5, expectedVersion);
                    ps.setLong(6, expectedLeaseEpoch);
                }) > 0;
    }

    /** 终态收尾：释放在途槽位、写错误与完成时间。 */
    public boolean finishFenced(UUID id, long expectedVersion, long expectedLeaseEpoch,
                                MediaJobStatus target, String errorCode, String errorSummary) {
        return jdbcTemplate.update("""
                        UPDATE media_job
                        SET status = ?, inflight_reserved = FALSE, error_code = ?, error_summary = ?,
                            blocked_reason = NULL, finished_at = now(), version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ? AND lease_epoch = ?
                        """,
                ps -> {
                    ps.setString(1, target.name());
                    if (errorCode == null) ps.setNull(2, java.sql.Types.VARCHAR);
                    else ps.setString(2, errorCode);
                    if (errorSummary == null) ps.setNull(3, java.sql.Types.VARCHAR);
                    else ps.setString(3, errorSummary);
                    ps.setObject(4, id);
                    ps.setLong(5, expectedVersion);
                    ps.setLong(6, expectedLeaseEpoch);
                }) > 0;
    }

    /** 释放在途槽位（进入终态/取消确认后）。 */
    public boolean releaseInflightFenced(UUID id, long expectedVersion, long expectedLeaseEpoch) {
        return jdbcTemplate.update("""
                        UPDATE media_job SET inflight_reserved = FALSE, version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ? AND lease_epoch = ?
                        """,
                id, expectedVersion, expectedLeaseEpoch) > 0;
    }

    /**
     * 调度候选：未终态、到期、租约过期（SKIP LOCKED 由调用方事务提供）。
     * SUBMITTING 不进入普通认领：提交结果不明，只能由恢复器判定去向，普通 tick 空操作
     * 会把租约清成 NULL 导致周期恢复永远选不到（R1）。
     */
    public List<MediaJobRecord> findDueForClaim(Instant now, int limit) {
        return jdbcTemplate.query(SELECT + """
                        WHERE status IN ('QUEUED','RECONCILING','RUNNING','CANCEL_REQUESTED','FETCHING','INGEST_FAILED')
                          AND next_action_at <= ?
                          AND (lease_until IS NULL OR lease_until < ?)
                        ORDER BY next_action_at
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """,
                MAPPER, Timestamp.from(now), Timestamp.from(now), limit);
    }

    /** 释放租约：动作完成后让 next_action_at 决定下一次认领，避免被 60 秒租约拖慢查询。 */
    public void releaseLease(UUID id) {
        jdbcTemplate.update("UPDATE media_job SET lease_owner = NULL, lease_until = NULL WHERE id = ?", id);
    }

    /** 带 epoch 围栏的释放：若任务已被新 worker 接管（epoch 变化），不得清除其租约。 */
    public void releaseLease(UUID id, long expectedLeaseEpoch) {
        jdbcTemplate.update("""
                UPDATE media_job SET lease_owner = NULL, lease_until = NULL
                WHERE id = ? AND lease_epoch = ?
                """, id, expectedLeaseEpoch);
    }

    /**
     * 认领令牌：认领事务当场冻结的 (jobId, owner, epoch)。调用方后续执行、回写、释放
     * 都必须使用该令牌，不得重新读取记录取 epoch——否则旧 worker 会拿到新 worker 的
     * epoch，围栏失效（R2）。
     */
    public record ClaimToken(UUID jobId, String owner, long leaseEpoch) {
    }

    /** 认领：写租约持有者与 epoch（在同一事务内执行），返回冻结的认领令牌。 */
    public ClaimToken claim(UUID id, String workerId, Instant leaseUntil) {
        return jdbcTemplate.queryForObject("""
                        UPDATE media_job SET lease_owner = ?, lease_until = ?, lease_epoch = lease_epoch + 1
                        WHERE id = ?
                        RETURNING id, lease_owner, lease_epoch
                        """,
                (rs, rowNum) -> new ClaimToken(UUID.fromString(rs.getString("id")),
                        rs.getString("lease_owner"), rs.getLong("lease_epoch")),
                workerId, Timestamp.from(leaseUntil), id);
    }

    /**
     * 同步适配器提交前的租约延长：只以认领令牌的 epoch 围栏，不动 version
     * （租约延长不是状态迁移，version 仍归状态写回路径管理）。
     */
    public boolean extendLeaseFenced(UUID id, long expectedLeaseEpoch, Instant leaseUntil) {
        return jdbcTemplate.update("""
                        UPDATE media_job SET lease_until = ?, updated_at = now()
                        WHERE id = ? AND lease_epoch = ?
                        """,
                Timestamp.from(leaseUntil), id, expectedLeaseEpoch) > 0;
    }

    /**
     * 进入 RECONCILING 并写入观察截止时间：同步适配器（无上游任务号可对账）在
     * 观察窗口过后仍未获得结果时，由查询路径如实收敛为 FAILED。
     */
    public boolean reconcileUncertainFenced(UUID id, long expectedVersion, long expectedLeaseEpoch,
                                            Instant nextActionAt, Instant observationDeadline) {
        return jdbcTemplate.update("""
                        UPDATE media_job
                        SET status = ?, next_action_at = ?, observation_deadline_at = ?,
                            version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ? AND lease_epoch = ?
                        """,
                ps -> {
                    ps.setString(1, MediaJobStatus.RECONCILING.name());
                    ps.setTimestamp(2, Timestamp.from(nextActionAt));
                    ps.setTimestamp(3, observationDeadline == null ? null : Timestamp.from(observationDeadline));
                    ps.setObject(4, id);
                    ps.setLong(5, expectedVersion);
                    ps.setLong(6, expectedLeaseEpoch);
                }) > 0;
    }

    /** 统计某作用域下占用在途槽位的活跃任务数。 */
    public int countReserved(String scopeType, Object scopeId) {
        return switch (scopeType) {
            case "GLOBAL" -> countReservedBy("1 = 1", null);
            case "PROVIDER" -> countReservedBy("AND provider_id = ?", scopeId);
            case "USER" -> countReservedBy("AND user_id = ?", scopeId);
            default -> 0;
        };
    }

    private int countReservedBy(String condition, Object scopeId) {
        String sql = """
                SELECT count(*) FROM media_job
                WHERE inflight_reserved = TRUE AND status IN
                    ('SUBMITTING','RECONCILING','RUNNING','CANCEL_REQUESTED','FETCHING','INGEST_FAILED')
                """ + (scopeId == null ? "" : " " + condition);
        Integer count = scopeId == null
                ? jdbcTemplate.queryForObject(sql, Integer.class)
                : jdbcTemplate.queryForObject(sql, Integer.class, scopeId);
        return count == null ? 0 : count;
    }

    /** 指定状态的任务数（排队上限检查用）。 */
    public int countByOwnerAndStatus(String tenantId, String userId, MediaJobStatus status) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM media_job WHERE tenant_id = ? AND user_id = ? AND status = ?
                        """,
                Integer.class, tenantId, userId, status.name());
        return count == null ? 0 : count;
    }

    public List<MediaJobRecord> listByOwner(String tenantId, String userId,
                                            Instant cursorCreatedAt, UUID cursorId, int limit) {
        return listByOwner(tenantId, userId, null, cursorCreatedAt, cursorId, limit);
    }

    public List<MediaJobRecord> listByOwner(String tenantId, String userId, UUID projectId,
                                            Instant cursorCreatedAt, UUID cursorId, int limit) {
        String projectClause = projectId == null ? "" : " AND project_id = ?";
        if (cursorCreatedAt == null || cursorId == null) {
            String sql = SELECT + "WHERE tenant_id = ? AND user_id = ?" + projectClause
                    + " ORDER BY created_at DESC, id DESC LIMIT ?";
            return projectId == null
                    ? jdbcTemplate.query(sql, MAPPER, tenantId, userId, limit)
                    : jdbcTemplate.query(sql, MAPPER, tenantId, userId, projectId, limit);
        }
        String sql = SELECT + "WHERE tenant_id = ? AND user_id = ?" + projectClause
                + " AND (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT ?";
        return projectId == null
                ? jdbcTemplate.query(sql, MAPPER, tenantId, userId,
                Timestamp.from(cursorCreatedAt), cursorId, limit)
                : jdbcTemplate.query(sql, MAPPER, tenantId, userId, projectId,
                Timestamp.from(cursorCreatedAt), cursorId, limit);
    }

    private static MediaJobRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new MediaJobRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                JobSourceType.valueOf(rs.getString("source_type")),
                rs.getString("source_run_id"),
                rs.getString("source_node_id"),
                (UUID) rs.getObject("parent_job_id"),
                rs.getString("operation"),
                MediaJobStatus.valueOf(rs.getString("status")),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                CreativeJson.fromJson(rs.getString("input_snapshot"), InputSnapshot.class),
                CreativeJson.fromJson(rs.getString("model_snapshot"), ModelSnapshot.class),
                (Long) rs.getObject("provider_id"),
                rs.getString("provider_task_id"),
                rs.getString("submission_key"),
                rs.getLong("version"),
                rs.getString("lease_owner"),
                toInstant(rs.getTimestamp("lease_until")),
                rs.getLong("lease_epoch"),
                rs.getBoolean("inflight_reserved"),
                toInstant(rs.getTimestamp("next_action_at")),
                rs.getString("blocked_reason"),
                rs.getBoolean("attention_required"),
                rs.getString("error_code"),
                rs.getString("error_summary"),
                toInstant(rs.getTimestamp("cancel_requested_at")),
                toInstant(rs.getTimestamp("observation_deadline_at")),
                toInstant(rs.getTimestamp("reconcile_after")),
                rs.getString("manual_resolution"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                toInstant(rs.getTimestamp("finished_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
