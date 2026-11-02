package com.zwx.zwxagent.creative.repository;

import com.zwx.zwxagent.creative.domain.VideoBatchItemRecord;
import com.zwx.zwxagent.creative.domain.VideoBatchRecord;
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
 * video_production_batch / video_batch_item 数据访问（V18）。
 * 唯一意图 UNIQUE(batch_id, shot_id) 在 DB 层拒绝重复子项；提交与停止都是条件更新。
 */
@Repository
public class VideoBatchRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<VideoBatchRecord> BATCH = VideoBatchRepository::mapBatch;
    private static final RowMapper<VideoBatchItemRecord> ITEM = VideoBatchRepository::mapItem;

    public VideoBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertBatch(VideoBatchRecord batch) {
        jdbcTemplate.update("""
                        INSERT INTO video_production_batch (id, project_id, tenant_id, user_id,
                                                            scope_label, status, concurrency)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, batch.id());
                    ps.setObject(2, batch.projectId());
                    ps.setString(3, batch.tenantId());
                    ps.setString(4, batch.userId());
                    ps.setString(5, batch.scopeLabel());
                    ps.setString(6, batch.status());
                    ps.setInt(7, batch.concurrency());
                });
    }

    public Optional<VideoBatchRecord> findBatch(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query("SELECT * FROM video_production_batch WHERE id = ? AND tenant_id = ? AND user_id = ?",
                BATCH, id, tenantId, userId).stream().findFirst();
    }

    public List<VideoBatchRecord> listBatches(String tenantId, String userId, UUID projectId, int limit) {
        return jdbcTemplate.query("""
                        SELECT * FROM video_production_batch
                        WHERE project_id = ? AND tenant_id = ? AND user_id = ?
                        ORDER BY created_at DESC LIMIT ?
                        """, BATCH, projectId, tenantId, userId, limit);
    }

    /** 提交调度扫描：仍在接收提交的批次（每批取最旧优先，避免饥饿）。 */
    public List<VideoBatchRecord> listRunningBatches(int limit) {
        return jdbcTemplate.query("""
                        SELECT * FROM video_production_batch WHERE status = 'RUNNING'
                        ORDER BY created_at ASC LIMIT ?
                        """, BATCH, limit);
    }

    public boolean updateBatchStatus(UUID id, String status) {
        return jdbcTemplate.update("""
                        UPDATE video_production_batch SET status = ?, updated_at = now() WHERE id = ?
                        """, status, id) > 0;
    }

    /** 批次完成/停止收敛：RUNNING/STOPPING → 终态，仅在未提交子项已清空时成立。 */
    public boolean finalizeBatchIfSettled(UUID id) {
        return jdbcTemplate.update("""
                        UPDATE video_production_batch b SET status = CASE
                                WHEN EXISTS (SELECT 1 FROM video_batch_item i
                                             WHERE i.batch_id = b.id AND i.status = 'SKIPPED')
                                    THEN 'STOPPED' ELSE 'COMPLETED' END,
                            updated_at = now()
                        WHERE b.id = ? AND b.status IN ('RUNNING', 'STOPPING')
                          AND NOT EXISTS (SELECT 1 FROM video_batch_item i
                                          WHERE i.batch_id = b.id
                                            AND i.status IN ('PENDING_SUBMIT', 'SUBMITTED'))
                        """, id) > 0;
    }

    public void insertItem(VideoBatchItemRecord item) {
        jdbcTemplate.update("""
                        INSERT INTO video_batch_item (id, project_id, tenant_id, user_id, batch_id,
                                                      shot_id, status, candidate_id, job_id, attempt, error)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, item.id());
                    ps.setObject(2, item.projectId());
                    ps.setString(3, item.tenantId());
                    ps.setString(4, item.userId());
                    ps.setObject(5, item.batchId());
                    ps.setObject(6, item.shotId());
                    ps.setString(7, item.status());
                    if (item.candidateId() == null) ps.setNull(8, java.sql.Types.OTHER);
                    else ps.setObject(8, item.candidateId());
                    if (item.jobId() == null) ps.setNull(9, java.sql.Types.OTHER);
                    else ps.setObject(9, item.jobId());
                    ps.setInt(10, item.attempt());
                    ps.setString(11, item.error());
                });
    }

    public Optional<VideoBatchItemRecord> findItem(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query("SELECT * FROM video_batch_item WHERE id = ? AND tenant_id = ? AND user_id = ?",
                ITEM, id, tenantId, userId).stream().findFirst();
    }

    public List<VideoBatchItemRecord> listItems(String tenantId, String userId, UUID batchId) {
        return jdbcTemplate.query("""
                        SELECT i.* FROM video_batch_item i
                        JOIN video_shot sh ON sh.id = i.shot_id
                        JOIN video_scene sc ON sc.id = sh.scene_id
                        JOIN video_episode e ON e.id = sc.episode_id
                        WHERE i.batch_id = ? AND i.tenant_id = ? AND i.user_id = ?
                        ORDER BY e.episode_number ASC, sc.scene_number ASC, sh.shot_number ASC
                        """, ITEM, batchId, tenantId, userId);
    }

    /** 提交调度取待提交子项（同事务内配额判断后逐个提交）。 */
    public List<VideoBatchItemRecord> listPendingItems(UUID batchId, int limit) {
        return jdbcTemplate.query("""
                        SELECT * FROM video_batch_item
                        WHERE batch_id = ? AND status = 'PENDING_SUBMIT'
                        ORDER BY created_at ASC LIMIT ?
                        """, ITEM, batchId, limit);
    }

    public int countInflight(UUID batchId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM video_batch_item
                        WHERE batch_id = ? AND status = 'SUBMITTED'
                        """, Integer.class, batchId);
        return count == null ? 0 : count;
    }

    public boolean markSubmitted(UUID id, UUID candidateId, UUID jobId, int attempt) {
        return jdbcTemplate.update("""
                        UPDATE video_batch_item SET status = 'SUBMITTED', candidate_id = ?, job_id = ?,
                            attempt = ?, error = NULL, updated_at = now()
                        WHERE id = ? AND status = 'PENDING_SUBMIT'
                        """, candidateId, jobId, attempt, id) > 0;
    }

    public boolean markFailed(UUID id, String error) {
        return jdbcTemplate.update("""
                        UPDATE video_batch_item SET status = 'FAILED', error = ?, updated_at = now()
                        WHERE id = ? AND status IN ('PENDING_SUBMIT', 'SUBMITTED')
                        """, error, id) > 0;
    }

    /** 子项终态化（候选回写联动）：SUBMITTED → DONE/FAILED，按候选状态判定。 */
    public boolean finalizeItem(UUID candidateId, boolean success) {
        return jdbcTemplate.update("""
                        UPDATE video_batch_item SET status = ?, updated_at = now()
                        WHERE candidate_id = ? AND status = 'SUBMITTED'
                        """, success ? "DONE" : "FAILED", candidateId) > 0;
    }

    public Optional<UUID> batchIdOfCandidate(UUID candidateId) {
        return jdbcTemplate.query(
                "SELECT batch_id FROM video_batch_item WHERE candidate_id = ? LIMIT 1",
                (rs, rowNum) -> UUID.fromString(rs.getString("batch_id")), candidateId).stream().findFirst();
    }

    /** 停止：未提交子项 → SKIPPED（在途不动）；返回影响行数供聚合展示。 */
    public int skipPending(UUID batchId) {
        return jdbcTemplate.update("""
                        UPDATE video_batch_item SET status = 'SKIPPED', updated_at = now()
                        WHERE batch_id = ? AND status = 'PENDING_SUBMIT'
                        """, batchId);
    }

    /** 重试失败项：FAILED → PENDING_SUBMIT（attempt 由提交侧自增），旧候选保留。 */
    public int resetFailedForRetry(UUID batchId) {
        return jdbcTemplate.update("""
                        UPDATE video_batch_item SET status = 'PENDING_SUBMIT', error = NULL, updated_at = now()
                        WHERE batch_id = ? AND status = 'FAILED'
                        """, batchId);
    }

    /** 提交失败退避重试：FAILED → PENDING_SUBMIT 并记录 attempt（仅未达上限时调用）。 */
    public boolean resetItemForRetry(UUID id, int attempt) {
        return jdbcTemplate.update("""
                        UPDATE video_batch_item SET status = 'PENDING_SUBMIT', attempt = ?, updated_at = now()
                        WHERE id = ? AND status = 'FAILED'
                        """, attempt, id) > 0;
    }

    public boolean deleteItem(UUID id) {
        return jdbcTemplate.update("DELETE FROM video_batch_item WHERE id = ?", id) > 0;
    }

    // ---------------------------------------------------------------- mappers

    private static VideoBatchRecord mapBatch(ResultSet rs, int rowNum) throws SQLException {
        return new VideoBatchRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("scope_label"),
                rs.getString("status"),
                rs.getInt("concurrency"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static VideoBatchItemRecord mapItem(ResultSet rs, int rowNum) throws SQLException {
        UUID candidateId = uuidOrNull(rs, "candidate_id");
        UUID jobId = uuidOrNull(rs, "job_id");
        return new VideoBatchItemRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                UUID.fromString(rs.getString("batch_id")),
                UUID.fromString(rs.getString("shot_id")),
                rs.getString("status"),
                candidateId,
                jobId,
                rs.getInt("attempt"),
                rs.getString("error"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static UUID uuidOrNull(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof UUID uuid) return uuid;
        if (value != null) return UUID.fromString(value.toString());
        return null;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
