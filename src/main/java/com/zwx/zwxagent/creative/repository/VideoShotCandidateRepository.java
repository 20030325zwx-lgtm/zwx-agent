package com.zwx.zwxagent.creative.repository;

import com.zwx.zwxagent.creative.domain.VideoShotCandidateRecord;
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
 * video_shot_candidate 数据访问（V17）。
 * 单选约束由部分唯一索引 uq_video_candidate_selected 保证；换选事务内先退回旧候选再选定新候选。
 */
@Repository
public class VideoShotCandidateRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<VideoShotCandidateRecord> MAPPER = VideoShotCandidateRepository::map;

    public VideoShotCandidateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(VideoShotCandidateRecord candidate) {
        jdbcTemplate.update("""
                        INSERT INTO video_shot_candidate (id, project_id, tenant_id, user_id, shot_id,
                                                          asset_id, job_id, source, prompt_text, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, candidate.id());
                    ps.setObject(2, candidate.projectId());
                    ps.setString(3, candidate.tenantId());
                    ps.setString(4, candidate.userId());
                    ps.setObject(5, candidate.shotId());
                    if (candidate.assetId() == null) ps.setNull(6, java.sql.Types.OTHER);
                    else ps.setObject(6, candidate.assetId());
                    if (candidate.jobId() == null) ps.setNull(7, java.sql.Types.OTHER);
                    else ps.setObject(7, candidate.jobId());
                    ps.setString(8, candidate.source());
                    ps.setString(9, candidate.promptText());
                    ps.setString(10, candidate.status());
                });
    }

    public Optional<VideoShotCandidateRecord> findById(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query("SELECT * FROM video_shot_candidate WHERE id = ? AND tenant_id = ? AND user_id = ?",
                MAPPER, id, tenantId, userId).stream().findFirst();
    }

    public List<VideoShotCandidateRecord> listByShot(String tenantId, String userId, UUID shotId) {
        return jdbcTemplate.query("""
                        SELECT * FROM video_shot_candidate
                        WHERE shot_id = ? AND tenant_id = ? AND user_id = ?
                        ORDER BY created_at DESC
                        """, MAPPER, shotId, tenantId, userId);
    }

    /** 候选 + 关联任务状态 + 产物素材摘要（检查器直接显示分辨率/时长，不再只有 assetId）。 */
    public List<CandidateWithJob> listByShotWithJob(String tenantId, String userId, UUID shotId) {
        return jdbcTemplate.query("""
                        SELECT c.*, j.status AS job_status, j.error_summary AS job_error,
                               a.display_name AS asset_name, a.media_type AS asset_media_type,
                               a.width AS asset_width, a.height AS asset_height,
                               a.duration_ms AS asset_duration_ms, a.byte_size AS asset_byte_size
                        FROM video_shot_candidate c
                        LEFT JOIN media_job j ON j.id = c.job_id
                        LEFT JOIN creative_asset a ON a.id = c.asset_id
                        WHERE c.shot_id = ? AND c.tenant_id = ? AND c.user_id = ?
                        ORDER BY c.created_at DESC
                        """, (rs, rowNum) -> new CandidateWithJob(map(rs, rowNum),
                rs.getString("job_status"), rs.getString("job_error"),
                new CandidateAsset(
                        rs.getString("asset_name"),
                        rs.getString("asset_media_type"),
                        (Integer) rs.getObject("asset_width"),
                        (Integer) rs.getObject("asset_height"),
                        rs.getObject("asset_duration_ms") == null ? null : rs.getLong("asset_duration_ms"),
                        rs.getObject("asset_byte_size") == null ? null : rs.getLong("asset_byte_size"))),
                shotId, tenantId, userId);
    }

    /** 候选产物摘要；生成中或失败时没有产物，整个对象为空。 */
    public record CandidateAsset(String displayName, String mediaType, Integer width, Integer height,
                                 Long durationMs, Long byteSize) {
    }

    public record CandidateWithJob(VideoShotCandidateRecord candidate, String jobStatus, String jobError,
                                   CandidateAsset asset) {
    }

    public Optional<VideoShotCandidateRecord> findSelected(String tenantId, String userId, UUID shotId) {
        return jdbcTemplate.query("""
                        SELECT * FROM video_shot_candidate
                        WHERE shot_id = ? AND tenant_id = ? AND user_id = ? AND status = 'SELECTED'
                        """, MAPPER, shotId, tenantId, userId).stream().findFirst();
    }

    public boolean updateStatus(UUID id, String status) {
        return jdbcTemplate.update(
                "UPDATE video_shot_candidate SET status = ?, updated_at = now() WHERE id = ?", status, id) > 0;
    }

    /** 受理后回填任务 ID（UNIQUE(job_id) 保证一候选一任务）。 */
    public boolean attachJob(UUID id, UUID jobId) {
        return jdbcTemplate.update(
                "UPDATE video_shot_candidate SET job_id = ?, updated_at = now() WHERE id = ? AND job_id IS NULL",
                jobId, id) > 0;
    }

    /** 回写调度器扫描：GENERATING 且其任务已终态的候选（R02 模式：只认终态，防队首阻塞）。 */
    public List<TerminalCandidateRow> listTerminalGenerating(int limit) {
        return jdbcTemplate.query("""
                        SELECT c.id, c.shot_id, c.job_id, c.project_id, c.tenant_id, c.user_id, j.status AS job_status
                        FROM video_shot_candidate c
                        JOIN media_job j ON j.id = c.job_id
                        WHERE c.status = 'GENERATING'
                          AND j.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                        ORDER BY c.created_at ASC
                        LIMIT ?
                        """, (rs, rowNum) -> new TerminalCandidateRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("shot_id")),
                UUID.fromString(rs.getString("job_id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("job_status")), limit);
    }

    public record TerminalCandidateRow(UUID id, UUID shotId, UUID jobId, UUID projectId,
                                       String tenantId, String userId, String jobStatus) {
    }

    /** 生成成功后取首个入库产物资产（同 WorkflowMediaResumeScheduler 的查询）。 */
    public Optional<UUID> firstDoneAsset(UUID jobId) {
        return jdbcTemplate.query("""
                        SELECT a.id FROM media_job_output o
                        JOIN creative_asset a ON a.id = o.asset_id
                        WHERE o.job_id = ? AND o.ingest_state = 'DONE'
                        ORDER BY o.output_index LIMIT 1
                        """, (rs, rowNum) -> UUID.fromString(rs.getString("id")), jobId).stream().findFirst();
    }

    /** 生成回写：把产物资产挂到候选并进入待选片（仅 GENERATING 态可回写，幂等）。 */
    public boolean attachAsset(UUID id, UUID assetId) {
        return jdbcTemplate.update("""
                        UPDATE video_shot_candidate
                        SET asset_id = ?, status = 'CANDIDATE', updated_at = now()
                        WHERE id = ? AND status = 'GENERATING'
                        """, assetId, id) > 0;
    }

    public boolean markFailed(UUID id) {
        return jdbcTemplate.update("""
                        UPDATE video_shot_candidate SET status = 'FAILED', updated_at = now()
                        WHERE id = ? AND status = 'GENERATING'
                        """, id) > 0;
    }

    /** 换选：把该镜头当前 SELECTED 候选退回 CANDIDATE（新选定在同一事务内随后执行）。 */
    public boolean demoteSelected(UUID shotId) {
        return jdbcTemplate.update("""
                        UPDATE video_shot_candidate SET status = 'CANDIDATE', updated_at = now()
                        WHERE shot_id = ? AND status = 'SELECTED'
                        """, shotId) > 0;
    }

    public boolean delete(UUID id) {
        return jdbcTemplate.update("DELETE FROM video_shot_candidate WHERE id = ?", id) > 0;
    }

    public boolean deleteByShot(UUID shotId) {
        return jdbcTemplate.update("DELETE FROM video_shot_candidate WHERE shot_id = ?", shotId) > 0;
    }

    public int countByShot(String tenantId, String userId, UUID shotId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM video_shot_candidate
                        WHERE shot_id = ? AND tenant_id = ? AND user_id = ?
                        """, Integer.class, shotId, tenantId, userId);
        return count == null ? 0 : count;
    }

    /** 全项目候选（工作区汇总用）。 */
    public List<VideoShotCandidateRecord> listByProject(String tenantId, String userId, UUID projectId) {
        return jdbcTemplate.query("""
                        SELECT * FROM video_shot_candidate
                        WHERE project_id = ? AND tenant_id = ? AND user_id = ?
                        ORDER BY created_at DESC
                        """, MAPPER, projectId, tenantId, userId);
    }

    /** 素材反查：素材被哪些候选（含所属镜头）引用——用于素材库「用于镜头」与删除保护展示。 */
    public List<CandidateUsageView> listUsageByAsset(String tenantId, String userId, UUID projectId, UUID assetId) {
        return jdbcTemplate.query("""
                        SELECT c.id AS candidate_id, c.shot_id, c.status, sh.shot_number, sc.scene_number,
                               e.episode_number, e.title AS episode_title
                        FROM video_shot_candidate c
                        JOIN video_shot sh ON sh.id = c.shot_id
                        JOIN video_scene sc ON sc.id = sh.scene_id
                        JOIN video_episode e ON e.id = sc.episode_id
                        WHERE c.project_id = ? AND c.tenant_id = ? AND c.user_id = ?
                          AND c.asset_id = ?
                        ORDER BY c.created_at DESC
                        """, (rs, rowNum) -> new CandidateUsageView(
                UUID.fromString(rs.getString("candidate_id")),
                UUID.fromString(rs.getString("shot_id")),
                rs.getString("status"),
                rs.getInt("episode_number"),
                rs.getString("episode_title"),
                rs.getInt("scene_number"),
                rs.getInt("shot_number")),
                projectId, tenantId, userId, assetId);
    }

    public record CandidateUsageView(UUID candidateId, UUID shotId, String status,
                                     int episodeNumber, String episodeTitle,
                                     int sceneNumber, int shotNumber) {
    }

    private static VideoShotCandidateRecord map(ResultSet rs, int rowNum) throws SQLException {
        UUID assetId = null;
        Object assetValue = rs.getObject("asset_id");
        if (assetValue instanceof UUID uuid) assetId = uuid;
        else if (assetValue != null) assetId = UUID.fromString(assetValue.toString());
        UUID jobId = null;
        Object jobValue = rs.getObject("job_id");
        if (jobValue instanceof UUID uuid) jobId = uuid;
        else if (jobValue != null) jobId = UUID.fromString(jobValue.toString());
        return new VideoShotCandidateRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                UUID.fromString(rs.getString("shot_id")),
                assetId,
                jobId,
                rs.getString("source"),
                rs.getString("prompt_text"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
