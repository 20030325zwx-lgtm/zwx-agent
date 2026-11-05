package com.zwx.zwxagent.creative.repository;

import com.zwx.zwxagent.creative.domain.VideoEpisodeRecord;
import com.zwx.zwxagent.creative.domain.VideoSceneRecord;
import com.zwx.zwxagent.creative.domain.VideoShotRecord;
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
 * video_episode / video_scene / video_shot 数据访问（V17）。
 * 所有查询带 tenant_id + user_id；层级归属（场次→剧集→项目）由组合外键在 DB 层兜底，
 * 服务层仍需显式校验父子关系（跨项目引用隔离不依赖前端）。
 */
@Repository
public class VideoStoryContentRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<VideoEpisodeRecord> EPISODE = VideoStoryContentRepository::mapEpisode;
    private static final RowMapper<VideoSceneRecord> SCENE = VideoStoryContentRepository::mapScene;
    private static final RowMapper<VideoShotRecord> SHOT = VideoStoryContentRepository::mapShot;

    public VideoStoryContentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------------------------------------------------------- 剧集

    public void insertEpisode(VideoEpisodeRecord episode) {
        jdbcTemplate.update("""
                        INSERT INTO video_episode (id, project_id, tenant_id, user_id, episode_number,
                                                   title, goal, synopsis, script, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, episode.id());
                    ps.setObject(2, episode.projectId());
                    ps.setString(3, episode.tenantId());
                    ps.setString(4, episode.userId());
                    ps.setInt(5, episode.episodeNumber());
                    ps.setString(6, episode.title());
                    ps.setString(7, episode.goal());
                    ps.setString(8, episode.synopsis());
                    ps.setString(9, episode.script());
                    ps.setString(10, episode.status());
                });
    }

    public List<VideoEpisodeRecord> listEpisodes(String tenantId, String userId, UUID projectId) {
        return jdbcTemplate.query("""
                        SELECT * FROM video_episode
                        WHERE project_id = ? AND tenant_id = ? AND user_id = ?
                        ORDER BY episode_number ASC, created_at ASC
                        """, EPISODE, projectId, tenantId, userId);
    }

    public Optional<VideoEpisodeRecord> findEpisode(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query("SELECT * FROM video_episode WHERE id = ? AND tenant_id = ? AND user_id = ?",
                EPISODE, id, tenantId, userId).stream().findFirst();
    }

    public int nextEpisodeNumber(UUID projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(episode_number), 0) + 1 FROM video_episode WHERE project_id = ?",
                Integer.class, projectId);
    }

    public boolean updateEpisode(UUID id, String title, String goal, String synopsis, String script) {
        return jdbcTemplate.update("""
                        UPDATE video_episode SET title = ?, goal = ?, synopsis = ?, script = ?, updated_at = now()
                        WHERE id = ?
                        """, title, goal, synopsis, script, id) > 0;
    }

    /** 剧集顺序调整：调用方在事务内成对交换后各调用一次（同事务原子性由调用方保证）。 */
    public boolean updateEpisodeNumber(UUID id, int episodeNumber) {
        return jdbcTemplate.update(
                "UPDATE video_episode SET episode_number = ?, updated_at = now() WHERE id = ?",
                episodeNumber, id) > 0;
    }

    public boolean updateEpisodeStatus(UUID id, String status) {
        return jdbcTemplate.update(
                "UPDATE video_episode SET status = ?, updated_at = now() WHERE id = ?", status, id) > 0;
    }

    public boolean deleteEpisode(UUID id) {
        return jdbcTemplate.update("DELETE FROM video_episode WHERE id = ?", id) > 0;
    }

    // ---------------------------------------------------------------- 场次

    public void insertScene(VideoSceneRecord scene) {
        jdbcTemplate.update("""
                        INSERT INTO video_scene (id, project_id, tenant_id, user_id, episode_id, scene_number,
                                                 title, location_text, time_of_day, characters_text,
                                                 goal, conflict, entry_state, exit_state, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, scene.id());
                    ps.setObject(2, scene.projectId());
                    ps.setString(3, scene.tenantId());
                    ps.setString(4, scene.userId());
                    ps.setObject(5, scene.episodeId());
                    ps.setInt(6, scene.sceneNumber());
                    ps.setString(7, scene.title());
                    ps.setString(8, scene.locationText());
                    ps.setString(9, scene.timeOfDay());
                    ps.setString(10, scene.charactersText());
                    ps.setString(11, scene.goal());
                    ps.setString(12, scene.conflict());
                    ps.setString(13, scene.entryState());
                    ps.setString(14, scene.exitState());
                    ps.setString(15, scene.status());
                });
    }

    /** 全项目的场次（含所属剧集），供工作区一次取全后按剧集分组。 */
    public List<VideoSceneRecord> listScenesByProject(String tenantId, String userId, UUID projectId) {
        return jdbcTemplate.query("""
                        SELECT s.* FROM video_scene s
                        JOIN video_episode e ON e.id = s.episode_id
                        WHERE s.project_id = ? AND s.tenant_id = ? AND s.user_id = ?
                        ORDER BY e.episode_number ASC, s.scene_number ASC, s.created_at ASC
                        """, SCENE, projectId, tenantId, userId);
    }

    public Optional<VideoSceneRecord> findScene(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query("SELECT * FROM video_scene WHERE id = ? AND tenant_id = ? AND user_id = ?",
                SCENE, id, tenantId, userId).stream().findFirst();
    }

    public int nextSceneNumber(UUID episodeId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(scene_number), 0) + 1 FROM video_scene WHERE episode_id = ?",
                Integer.class, episodeId);
    }

    public boolean updateScene(UUID id, String title, String locationText, String timeOfDay,
                               String charactersText, String goal, String conflict,
                               String entryState, String exitState) {
        return jdbcTemplate.update("""
                        UPDATE video_scene SET title = ?, location_text = ?, time_of_day = ?, characters_text = ?,
                            goal = ?, conflict = ?, entry_state = ?, exit_state = ?, updated_at = now()
                        WHERE id = ?
                        """, title, locationText, timeOfDay, charactersText, goal, conflict,
                entryState, exitState, id) > 0;
    }

    public boolean updateSceneStatus(UUID id, String status) {
        return jdbcTemplate.update(
                "UPDATE video_scene SET status = ?, updated_at = now() WHERE id = ?", status, id) > 0;
    }

    public boolean deleteScene(UUID id) {
        return jdbcTemplate.update("DELETE FROM video_scene WHERE id = ?", id) > 0;
    }

    // ---------------------------------------------------------------- 镜头

    public void insertShot(VideoShotRecord shot) {
        jdbcTemplate.update("""
                        INSERT INTO video_shot (id, project_id, tenant_id, user_id, scene_id, shot_number,
                                                purpose, action, camera, dialogue, continuity,
                                                duration_target_seconds, prompt_text, status, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, shot.id());
                    ps.setObject(2, shot.projectId());
                    ps.setString(3, shot.tenantId());
                    ps.setString(4, shot.userId());
                    ps.setObject(5, shot.sceneId());
                    ps.setInt(6, shot.shotNumber());
                    ps.setString(7, shot.purpose());
                    ps.setString(8, shot.action());
                    ps.setString(9, shot.camera());
                    ps.setString(10, shot.dialogue());
                    ps.setString(11, shot.continuity());
                    ps.setInt(12, shot.durationTargetSeconds());
                    ps.setString(13, shot.promptText());
                    ps.setString(14, shot.status());
                    ps.setLong(15, shot.version());
                });
    }

    public List<VideoShotRecord> listShotsByProject(String tenantId, String userId, UUID projectId) {
        return jdbcTemplate.query("""
                        SELECT sh.* FROM video_shot sh
                        JOIN video_scene sc ON sc.id = sh.scene_id
                        JOIN video_episode e ON e.id = sc.episode_id
                        WHERE sh.project_id = ? AND sh.tenant_id = ? AND sh.user_id = ?
                        ORDER BY e.episode_number ASC, sc.scene_number ASC, sh.shot_number ASC, sh.created_at ASC
                        """, SHOT, projectId, tenantId, userId);
    }

    public Optional<VideoShotRecord> findShot(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query("SELECT * FROM video_shot WHERE id = ? AND tenant_id = ? AND user_id = ?",
                SHOT, id, tenantId, userId).stream().findFirst();
    }

    public int nextShotNumber(UUID sceneId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(shot_number), 0) + 1 FROM video_shot WHERE scene_id = ?",
                Integer.class, sceneId);
    }

    /**
     * 镜头字段更新（乐观锁）：version 不符返回 false，由服务层转 409。
     * 编辑字段本身不改变生产状态（READY 后再编辑仍 READY）。
     */
    public boolean updateShotFields(UUID id, long expectedVersion, String purpose, String action,
                                    String camera, String dialogue, String continuity,
                                    int durationTargetSeconds, String promptText) {
        return jdbcTemplate.update("""
                        UPDATE video_shot SET purpose = ?, action = ?, camera = ?, dialogue = ?, continuity = ?,
                            duration_target_seconds = ?, prompt_text = ?, version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ?
                        """, purpose, action, camera, dialogue, continuity, durationTargetSeconds,
                promptText, id, expectedVersion) > 0;
    }

    /** 状态与版本推进（生成受理/失败回退等），带乐观锁条件。 */
    public boolean updateShotStatus(UUID id, long expectedVersion, String status) {
        return jdbcTemplate.update("""
                        UPDATE video_shot SET status = ?, version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ?
                        """, status, id, expectedVersion) > 0;
    }

    /** 状态条件迁移（回写调度器用）：仅在当前状态匹配时推进，不依赖版本号。 */
    public boolean transitionShotStatus(UUID id, String expectedStatus, String newStatus) {
        return jdbcTemplate.update("""
                        UPDATE video_shot SET status = ?, version = version + 1, updated_at = now()
                        WHERE id = ? AND status = ?
                        """, newStatus, id, expectedStatus) > 0;
    }

    /** 镜头顺序号重排（排序/插入用）：只改 shot_number，不动 version——顺序不属于镜头内容。 */
    public boolean updateShotNumber(UUID id, int shotNumber) {
        return jdbcTemplate.update(
                "UPDATE video_shot SET shot_number = ?, updated_at = now() WHERE id = ?", shotNumber, id) > 0;
    }

    /** 场次内顺序号整体平移（插入镜头让位用）：仅用于唯一索引下的两段式换位，调用方包在事务里。 */
    public void shiftShotNumbers(UUID sceneId, int aboveNumber, int delta) {
        jdbcTemplate.update(
                "UPDATE video_shot SET shot_number = shot_number + ? WHERE scene_id = ? AND shot_number > ?",
                delta, sceneId, aboveNumber);
    }

    public boolean deleteShot(UUID id) {
        return jdbcTemplate.update("DELETE FROM video_shot WHERE id = ?", id) > 0;
    }

    public boolean deleteShotsOfScene(UUID sceneId) {
        return jdbcTemplate.update("DELETE FROM video_shot WHERE scene_id = ?", sceneId) > 0;
    }

    // ---------------------------------------------------------------- mappers

    private static VideoEpisodeRecord mapEpisode(ResultSet rs, int rowNum) throws SQLException {
        return new VideoEpisodeRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getInt("episode_number"),
                rs.getString("title"),
                rs.getString("goal"),
                rs.getString("synopsis"),
                rs.getString("script"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static VideoSceneRecord mapScene(ResultSet rs, int rowNum) throws SQLException {
        return new VideoSceneRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                UUID.fromString(rs.getString("episode_id")),
                rs.getInt("scene_number"),
                rs.getString("title"),
                rs.getString("location_text"),
                rs.getString("time_of_day"),
                rs.getString("characters_text"),
                rs.getString("goal"),
                rs.getString("conflict"),
                rs.getString("entry_state"),
                rs.getString("exit_state"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static VideoShotRecord mapShot(ResultSet rs, int rowNum) throws SQLException {
        return new VideoShotRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                UUID.fromString(rs.getString("scene_id")),
                rs.getInt("shot_number"),
                rs.getString("purpose"),
                rs.getString("action"),
                rs.getString("camera"),
                rs.getString("dialogue"),
                rs.getString("continuity"),
                rs.getInt("duration_target_seconds"),
                rs.getString("prompt_text"),
                rs.getString("status"),
                rs.getLong("version"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
