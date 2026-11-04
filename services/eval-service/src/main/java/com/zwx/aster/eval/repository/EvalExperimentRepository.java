package com.zwx.aster.eval.repository;

import com.zwx.aster.eval.domain.EvalExperimentRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 实验仓储：认领/租约仿主站 media_job（SKIP LOCKED 认领 + epoch 围栏），
 * 状态迁移用行锁 + 状态守卫。
 */
@Repository
public class EvalExperimentRepository {

    private static final String SELECT = """
            SELECT id, tenant_id, created_by, dataset_id, dataset_version_no, eval_type, status,
                   candidate::text AS candidate, git_commit, totals::text AS totals,
                   error_code, error_message, version, lease_owner, lease_until, lease_epoch,
                   concurrency, budget::text AS budget, resume_of,
                   started_at, finished_at, created_at, updated_at
            FROM platform.eval_experiment
            """;

    private static final RowMapper<EvalExperimentRecord> MAPPER = (rs, rowNum) -> new EvalExperimentRecord(
            UUID.fromString(rs.getString("id")),
            rs.getString("tenant_id"),
            rs.getString("created_by"),
            UUID.fromString(rs.getString("dataset_id")),
            rs.getInt("dataset_version_no"),
            rs.getString("eval_type"),
            rs.getString("status"),
            rs.getString("candidate"),
            rs.getString("git_commit"),
            rs.getString("totals"),
            rs.getString("error_code"),
            rs.getString("error_message"),
            rs.getLong("version"),
            rs.getString("lease_owner"),
            rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant(),
            rs.getLong("lease_epoch"),
            rs.getInt("concurrency"),
            rs.getString("budget"),
            rs.getString("resume_of") == null ? null : UUID.fromString(rs.getString("resume_of")),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public EvalExperimentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public EvalExperimentRecord insert(EvalExperimentRecord record) {
        jdbcTemplate.update("""
                        INSERT INTO platform.eval_experiment (id, tenant_id, created_by, dataset_id, dataset_version_no,
                                                              eval_type, status, candidate, git_commit,
                                                              concurrency, budget, resume_of)
                        VALUES (?, ?, ?, ?, ?, ?, 'queued', CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), ?)
                        """,
                record.id(), record.tenantId(), record.createdBy(), record.datasetId(),
                record.datasetVersionNo(), record.evalType(), record.candidateJson(), record.gitCommit(),
                record.concurrency(), record.budgetJson() == null ? "{}" : record.budgetJson(), record.resumeOf());
        return findById(record.id()).orElseThrow();
    }

    public Optional<EvalExperimentRecord> findById(UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** 调度候选：新入队 QUEUED；RUNNING 且租约过期（崩溃恢复，结果 upsert 幂等）。 */
    public List<EvalExperimentRecord> findDueForClaim(Instant now, int limit) {
        return jdbcTemplate.query(SELECT + """
                        WHERE status = 'queued'
                           OR (status = 'running' AND (lease_until IS NULL OR lease_until < ?))
                        ORDER BY created_at
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """, MAPPER, Timestamp.from(now), limit);
    }

    public record ClaimToken(UUID experimentId, String owner, long leaseEpoch) {
    }

    public ClaimToken claim(UUID id, String workerId, Instant leaseUntil) {
        return jdbcTemplate.queryForObject("""
                        UPDATE platform.eval_experiment
                        SET lease_owner = ?, lease_until = ?, lease_epoch = lease_epoch + 1, updated_at = now()
                        WHERE id = ?
                        RETURNING id, lease_owner, lease_epoch
                        """,
                (rs, rowNum) -> new ClaimToken(UUID.fromString(rs.getString("id")),
                        rs.getString("lease_owner"), rs.getLong("lease_epoch")),
                workerId, Timestamp.from(leaseUntil), id);
    }

    public boolean extendLeaseFenced(UUID id, long expectedLeaseEpoch, Instant leaseUntil) {
        return jdbcTemplate.update("""
                        UPDATE platform.eval_experiment SET lease_until = ?, updated_at = now()
                        WHERE id = ? AND lease_epoch = ?
                        """, Timestamp.from(leaseUntil), id, expectedLeaseEpoch) > 0;
    }

    public void releaseLease(UUID id, long expectedLeaseEpoch) {
        jdbcTemplate.update("""
                        UPDATE platform.eval_experiment SET lease_owner = NULL, lease_until = NULL
                        WHERE id = ? AND lease_epoch = ?
                        """, id, expectedLeaseEpoch);
    }

    public Optional<EvalExperimentRecord> lockById(UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ? FOR UPDATE", MAPPER, id).stream().findFirst();
    }

    public boolean markRunningGuarded(UUID id) {
        return jdbcTemplate.update("""
                        UPDATE platform.eval_experiment
                        SET status = 'running', started_at = COALESCE(started_at, now()), version = version + 1,
                            updated_at = now()
                        WHERE id = ? AND status IN ('queued', 'running')
                        """, id) > 0;
    }

    public boolean finishGuarded(UUID id, String status, String totalsJson,
                                 String errorCode, String errorMessage) {
        return jdbcTemplate.update("""
                        UPDATE platform.eval_experiment
                        SET status = ?, totals = CAST(? AS jsonb), error_code = ?, error_message = ?,
                            finished_at = now(), version = version + 1, updated_at = now()
                        WHERE id = ? AND status IN ('queued', 'running')
                        """, status, totalsJson, errorCode, errorMessage, id) > 0;
    }

    public List<EvalExperimentRecord> listRecent(String tenantId, Long cursorMillis, String cursorId, int limit) {
        if (cursorMillis == null || cursorId == null || cursorId.isBlank()) {
            return jdbcTemplate.query(SELECT + """
                            WHERE tenant_id = ? ORDER BY created_at DESC, id DESC LIMIT ?
                            """, MAPPER, tenantId, limit);
        }
        return jdbcTemplate.query(SELECT + """
                        WHERE tenant_id = ? AND (created_at, id) < (to_timestamp(? / 1000.0), ?)
                        ORDER BY created_at DESC, id DESC LIMIT ?
                        """, MAPPER, tenantId, cursorMillis, cursorId, limit);
    }
}
