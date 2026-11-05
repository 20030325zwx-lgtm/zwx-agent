package com.zwx.zwxagent.creative.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zwx.zwxagent.creative.domain.VideoFinalCutRecord;

import static com.zwx.zwxagent.creative.domain.VideoFinalCutRecord.*;
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
 * 成片仓库（P2）：DRAFT 草稿与导出运行快照同表不同行；
 * 状态推进全部用条件 UPDATE（认领/取消/重试幂等），乐观锁只用于草稿 items 编辑。
 */
@Repository
public class VideoFinalCutRepository {

    private static final RowMapper<VideoFinalCutRecord> CUT = VideoFinalCutRepository::mapCut;

    private final JdbcTemplate jdbcTemplate;

    public VideoFinalCutRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(VideoFinalCutRecord cut) {
        jdbcTemplate.update("""
                        INSERT INTO video_final_cut (id, project_id, tenant_id, user_id, status, items,
                            items_version, source_draft_id, idempotency_key)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, cut.id());
                    ps.setObject(2, cut.projectId());
                    ps.setString(3, cut.tenantId());
                    ps.setString(4, cut.userId());
                    ps.setString(5, cut.status());
                    ps.setString(6, CreativeJson.toJson(cut.items()));
                    ps.setLong(7, cut.itemsVersion());
                    ps.setObject(8, cut.sourceDraftId());
                    ps.setString(9, cut.idempotencyKey());
                });
    }

    /** 草稿片段更新（乐观锁）：仅 DRAFT 可编辑。 */
    public boolean updateDraftItems(UUID id, long expectedVersion, List<VideoFinalCutItem> items) {
        return jdbcTemplate.update("""
                        UPDATE video_final_cut SET items = ?::jsonb, items_version = items_version + 1,
                            updated_at = now()
                        WHERE id = ? AND items_version = ? AND status = 'DRAFT'
                        """, CreativeJson.toJson(items), id, expectedVersion) > 0;
    }

    public boolean deleteDraft(UUID id) {
        return jdbcTemplate.update(
                "DELETE FROM video_final_cut WHERE id = ? AND status = 'DRAFT'", id) > 0;
    }

    public Optional<VideoFinalCutRecord> find(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query("SELECT * FROM video_final_cut WHERE id = ? AND tenant_id = ? AND user_id = ?",
                CUT, id, tenantId, userId).stream().findFirst();
    }

    public List<VideoFinalCutRecord> listByProject(String tenantId, String userId, UUID projectId, int limit) {
        return jdbcTemplate.query("""
                        SELECT * FROM video_final_cut WHERE project_id = ? AND tenant_id = ? AND user_id = ?
                        ORDER BY created_at DESC LIMIT ?
                        """, CUT, projectId, tenantId, userId, limit);
    }

    public Optional<VideoFinalCutRecord> latestDraft(String tenantId, String userId, UUID projectId) {
        return jdbcTemplate.query("""
                        SELECT * FROM video_final_cut
                        WHERE project_id = ? AND tenant_id = ? AND user_id = ? AND status = 'DRAFT'
                        ORDER BY created_at DESC LIMIT 1
                        """, CUT, projectId, tenantId, userId).stream().findFirst();
    }

    /** 导出队列认领：PREPARING 最早优先，条件更新防并发双跑。 */
    public Optional<VideoFinalCutRecord> claimNextForExport() {
        return jdbcTemplate.query("""
                        UPDATE video_final_cut SET status = 'PROCESSING', updated_at = now()
                        WHERE id = (SELECT id FROM video_final_cut
                                    WHERE status = 'PREPARING' ORDER BY created_at ASC LIMIT 1)
                            AND status = 'PREPARING'
                        RETURNING *
                        """, CUT).stream().findFirst();
    }

    public boolean markUploading(UUID id) {
        return jdbcTemplate.update(
                "UPDATE video_final_cut SET status = 'UPLOADING', updated_at = now() WHERE id = ? AND status = 'PROCESSING'",
                id) > 0;
    }

    public boolean markCompleted(UUID id, UUID outputAssetId, long outputBytes, Long outputDurationMs) {
        return jdbcTemplate.update("""
                        UPDATE video_final_cut SET status = 'COMPLETED', output_asset_id = ?, output_bytes = ?,
                            output_duration_ms = ?, updated_at = now()
                        WHERE id = ? AND status IN ('PROCESSING', 'UPLOADING')
                        """, outputAssetId, outputBytes, outputDurationMs, id) > 0;
    }

    public boolean markFailed(UUID id, String error) {
        return jdbcTemplate.update("""
                        UPDATE video_final_cut SET status = 'FAILED', error = ?, updated_at = now()
                        WHERE id = ? AND status IN ('PREPARING', 'PROCESSING', 'UPLOADING')
                        """, error == null ? "" : error, id) > 0;
    }

    public boolean markCancelled(UUID id) {
        return jdbcTemplate.update("""
                        UPDATE video_final_cut SET status = 'CANCELLED', updated_at = now()
                        WHERE id = ? AND status IN ('PREPARING', 'PROCESSING', 'UPLOADING')
                        """, id) > 0;
    }

    public boolean markRunningForRetry(UUID id) {
        return jdbcTemplate.update("""
                        UPDATE video_final_cut SET status = 'PREPARING', error = '', updated_at = now()
                        WHERE id = ? AND status = 'FAILED'
                        """, id) > 0;
    }

    public boolean stillRunning(UUID id) {
        return jdbcTemplate.update("""
                        UPDATE video_final_cut SET updated_at = now()
                        WHERE id = ? AND status IN ('PREPARING', 'PROCESSING', 'UPLOADING')
                        """, id) > 0;
    }

    private static VideoFinalCutRecord mapCut(ResultSet rs, int rowNum) throws SQLException {
        return new VideoFinalCutRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("status"),
                parseItems(rs.getString("items")),
                rs.getLong("items_version"),
                Optional.ofNullable(rs.getString("output_asset_id")).map(UUID::fromString).orElse(null),
                rs.getObject("output_bytes") == null ? null : rs.getLong("output_bytes"),
                rs.getObject("output_duration_ms") == null ? null : rs.getLong("output_duration_ms"),
                rs.getString("output_size"),
                rs.getString("error"),
                Optional.ofNullable(rs.getString("source_draft_id")).map(UUID::fromString).orElse(null),
                rs.getString("idempotency_key"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static List<VideoFinalCutItem> parseItems(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<VideoFinalCutItem> items = CreativeJson.fromJson(json, new TypeReference<>() { });
            return items == null ? List.of() : items;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
