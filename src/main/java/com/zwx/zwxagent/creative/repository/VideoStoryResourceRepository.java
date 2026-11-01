package com.zwx.zwxagent.creative.repository;

import com.zwx.zwxagent.creative.domain.VideoCharacterRecord;
import com.zwx.zwxagent.creative.domain.VideoContinuityNoteRecord;
import com.zwx.zwxagent.creative.domain.VideoLocationRecord;
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
 * video_character / video_location / video_continuity_note / video_asset_ref 数据访问（V17）。
 * 角色与地点是跨集复用资源；参考图关联不复制素材。
 */
@Repository
public class VideoStoryResourceRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<VideoCharacterRecord> CHARACTER = VideoStoryResourceRepository::mapCharacter;
    private static final RowMapper<VideoLocationRecord> LOCATION = VideoStoryResourceRepository::mapLocation;
    private static final RowMapper<VideoContinuityNoteRecord> NOTE = VideoStoryResourceRepository::mapNote;

    public VideoStoryResourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------------------------------------------------------- 角色

    public void insertCharacter(VideoCharacterRecord record) {
        jdbcTemplate.update("""
                        INSERT INTO video_character (id, project_id, tenant_id, user_id, name,
                                                     appearance, personality, notes)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, record.id());
                    ps.setObject(2, record.projectId());
                    ps.setString(3, record.tenantId());
                    ps.setString(4, record.userId());
                    ps.setString(5, record.name());
                    ps.setString(6, record.appearance());
                    ps.setString(7, record.personality());
                    ps.setString(8, record.notes());
                });
    }

    public List<VideoCharacterRecord> listCharacters(String tenantId, String userId, UUID projectId) {
        return jdbcTemplate.query("""
                        SELECT * FROM video_character
                        WHERE project_id = ? AND tenant_id = ? AND user_id = ?
                        ORDER BY name ASC, created_at ASC
                        """, CHARACTER, projectId, tenantId, userId);
    }

    public Optional<VideoCharacterRecord> findCharacter(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query("SELECT * FROM video_character WHERE id = ? AND tenant_id = ? AND user_id = ?",
                CHARACTER, id, tenantId, userId).stream().findFirst();
    }

    public boolean updateCharacter(UUID id, String name, String appearance, String personality, String notes) {
        return jdbcTemplate.update("""
                        UPDATE video_character SET name = ?, appearance = ?, personality = ?, notes = ?,
                            updated_at = now()
                        WHERE id = ?
                        """, name, appearance, personality, notes, id) > 0;
    }

    public boolean deleteCharacter(UUID id) {
        return jdbcTemplate.update("DELETE FROM video_character WHERE id = ?", id) > 0;
    }

    // ---------------------------------------------------------------- 地点

    public void insertLocation(VideoLocationRecord record) {
        jdbcTemplate.update("""
                        INSERT INTO video_location (id, project_id, tenant_id, user_id, name, description)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, record.id());
                    ps.setObject(2, record.projectId());
                    ps.setString(3, record.tenantId());
                    ps.setString(4, record.userId());
                    ps.setString(5, record.name());
                    ps.setString(6, record.description());
                });
    }

    public List<VideoLocationRecord> listLocations(String tenantId, String userId, UUID projectId) {
        return jdbcTemplate.query("""
                        SELECT * FROM video_location
                        WHERE project_id = ? AND tenant_id = ? AND user_id = ?
                        ORDER BY name ASC, created_at ASC
                        """, LOCATION, projectId, tenantId, userId);
    }

    public boolean updateLocation(UUID id, String name, String description) {
        return jdbcTemplate.update(
                "UPDATE video_location SET name = ?, description = ?, updated_at = now() WHERE id = ?",
                name, description, id) > 0;
    }

    public boolean deleteLocation(UUID id) {
        return jdbcTemplate.update("DELETE FROM video_location WHERE id = ?", id) > 0;
    }

    // ---------------------------------------------------------------- 连续性记录

    public void insertNote(VideoContinuityNoteRecord record) {
        jdbcTemplate.update("""
                        INSERT INTO video_continuity_note (id, project_id, tenant_id, user_id, kind,
                                                           content, source_label, foreshadow_status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, record.id());
                    ps.setObject(2, record.projectId());
                    ps.setString(3, record.tenantId());
                    ps.setString(4, record.userId());
                    ps.setString(5, record.kind());
                    ps.setString(6, record.content());
                    ps.setString(7, record.sourceLabel());
                    ps.setString(8, record.foreshadowStatus());
                });
    }

    public List<VideoContinuityNoteRecord> listNotes(String tenantId, String userId, UUID projectId) {
        return jdbcTemplate.query("""
                        SELECT * FROM video_continuity_note
                        WHERE project_id = ? AND tenant_id = ? AND user_id = ?
                        ORDER BY kind ASC, created_at ASC
                        """, NOTE, projectId, tenantId, userId);
    }

    public boolean updateNote(UUID id, String content, String sourceLabel, String foreshadowStatus) {
        return jdbcTemplate.update("""
                        UPDATE video_continuity_note SET content = ?, source_label = ?, foreshadow_status = ?,
                            updated_at = now()
                        WHERE id = ?
                        """, content, sourceLabel, foreshadowStatus, id) > 0;
    }

    public boolean deleteNote(UUID id) {
        return jdbcTemplate.update("DELETE FROM video_continuity_note WHERE id = ?", id) > 0;
    }

    // ---------------------------------------------------------------- 参考图关联

    public void insertAssetRef(UUID id, UUID projectId, String tenantId, String userId,
                               String entityType, UUID entityId, UUID assetId) {
        jdbcTemplate.update("""
                        INSERT INTO video_asset_ref (id, project_id, tenant_id, user_id, entity_type, entity_id, asset_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (entity_type, entity_id, asset_id) DO NOTHING
                        """,
                ps -> {
                    ps.setObject(1, id);
                    ps.setObject(2, projectId);
                    ps.setString(3, tenantId);
                    ps.setString(4, userId);
                    ps.setString(5, entityType);
                    ps.setObject(6, entityId);
                    ps.setObject(7, assetId);
                });
    }

    public void deleteAssetRef(String entityType, UUID entityId, UUID assetId) {
        jdbcTemplate.update(
                "DELETE FROM video_asset_ref WHERE entity_type = ? AND entity_id = ? AND asset_id = ?",
                entityType, entityId, assetId);
    }

    /** 删除实体时清理其全部参考图关联。 */
    public void deleteAssetRefsByEntity(String entityType, UUID entityId) {
        jdbcTemplate.update("DELETE FROM video_asset_ref WHERE entity_type = ? AND entity_id = ?",
                entityType, entityId);
    }

    public List<UUID> listAssetRefs(String entityType, UUID entityId) {
        return jdbcTemplate.query(
                "SELECT asset_id FROM video_asset_ref WHERE entity_type = ? AND entity_id = ? ORDER BY created_at ASC",
                (rs, rowNum) -> UUID.fromString(rs.getString("asset_id")), entityType, entityId);
    }

    // ---------------------------------------------------------------- mappers

    private static VideoCharacterRecord mapCharacter(ResultSet rs, int rowNum) throws SQLException {
        return new VideoCharacterRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("name"),
                rs.getString("appearance"),
                rs.getString("personality"),
                rs.getString("notes"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static VideoLocationRecord mapLocation(ResultSet rs, int rowNum) throws SQLException {
        return new VideoLocationRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("name"),
                rs.getString("description"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static VideoContinuityNoteRecord mapNote(ResultSet rs, int rowNum) throws SQLException {
        return new VideoContinuityNoteRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("kind"),
                rs.getString("content"),
                rs.getString("source_label"),
                rs.getString("foreshadow_status"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
