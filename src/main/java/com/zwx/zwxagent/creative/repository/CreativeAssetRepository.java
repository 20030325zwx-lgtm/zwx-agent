package com.zwx.zwxagent.creative.repository;

import com.zwx.zwxagent.creative.domain.AssetOrigin;
import com.zwx.zwxagent.creative.domain.AssetStatus;
import com.zwx.zwxagent.creative.domain.CreativeAssetRecord;
import com.zwx.zwxagent.creative.domain.StorageBackend;
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

/** creative_asset 数据访问。删除为软删除（BR14）；内容读取必须带归属校验（BR16）。 */
@Repository
public class CreativeAssetRepository {

    private final JdbcTemplate jdbcTemplate;

    public CreativeAssetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<CreativeAssetRecord> MAPPER = CreativeAssetRepository::map;

    private static final String SELECT = """
            SELECT id, project_id, tenant_id, user_id, source_job_id, output_index, origin, media_type,
                   storage_backend, object_key, mime_type, byte_size, sha256, width, height, duration_ms,
                   display_name, starred, status, metadata, created_at, deleted_at
            FROM creative_asset
            """;

    public void insert(CreativeAssetRecord asset) {
        jdbcTemplate.update("""
                        INSERT INTO creative_asset
                            (id, project_id, tenant_id, user_id, source_job_id, output_index, origin,
                             media_type, storage_backend, object_key, mime_type, byte_size, sha256,
                             display_name, status, metadata)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        """,
                ps -> {
                    ps.setObject(1, asset.id());
                    if (asset.projectId() == null) ps.setNull(2, java.sql.Types.OTHER);
                    else ps.setObject(2, asset.projectId());
                    ps.setString(3, asset.tenantId());
                    ps.setString(4, asset.userId());
                    if (asset.sourceJobId() == null) ps.setNull(5, java.sql.Types.OTHER);
                    else ps.setObject(5, asset.sourceJobId());
                    if (asset.outputIndex() == null) ps.setNull(6, java.sql.Types.INTEGER);
                    else ps.setInt(6, asset.outputIndex());
                    ps.setString(7, asset.origin().name());
                    ps.setString(8, asset.mediaType());
                    ps.setString(9, asset.storageBackend().name());
                    ps.setString(10, asset.objectKey());
                    ps.setString(11, asset.mimeType());
                    ps.setLong(12, asset.byteSize());
                    ps.setString(13, asset.sha256());
                    ps.setString(14, asset.displayName());
                    ps.setString(15, asset.status().name());
                    ps.setString(16, asset.metadataJson() == null ? "{}" : asset.metadataJson());
                });
    }

    public Optional<CreativeAssetRecord> findByIdForOwner(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ? AND tenant_id = ? AND user_id = ?",
                MAPPER, id, tenantId, userId).stream().findFirst();
    }

    /** 列表：类型/收藏/项目筛选 + 游标分页；不返回已软删除项。 */
    public List<CreativeAssetRecord> listForOwner(String tenantId, String userId, UUID projectId,
                                                  String mediaType, boolean starred,
                                                  Instant cursorCreatedAt, UUID cursorId, int limit) {
        StringBuilder where = new StringBuilder(" WHERE tenant_id = ? AND user_id = ? AND status <> 'DELETED'");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        args.add(userId);
        if (projectId != null) {
            where.append(" AND project_id = ?");
            args.add(projectId);
        }
        if (mediaType != null && !mediaType.isBlank()) {
            where.append(" AND media_type = ?");
            args.add(mediaType);
        }
        if (starred) {
            where.append(" AND starred = TRUE");
        }
        if (cursorCreatedAt != null && cursorId != null) {
            where.append(" AND (created_at, id) < (?, ?)");
            args.add(Timestamp.from(cursorCreatedAt));
            args.add(cursorId);
        }
        where.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(SELECT + where, MAPPER, args.toArray());
    }

    public boolean markReady(UUID id, Integer width, Integer height, Long durationMs) {
        return jdbcTemplate.update("""
                        UPDATE creative_asset SET status = ?, width = ?, height = ?, duration_ms = ?
                        WHERE id = ?
                        """,
                AssetStatus.READY.name(), width, height, durationMs, id) > 0;
    }

    public boolean updateDisplay(UUID id, String displayName, boolean starred) {
        return jdbcTemplate.update("""
                        UPDATE creative_asset SET display_name = ?, starred = ?
                        WHERE id = ? AND status <> 'DELETED'
                        """,
                displayName, starred, id) > 0;
    }

    public boolean softDelete(UUID id) {
        return jdbcTemplate.update("""
                        UPDATE creative_asset SET status = ?, deleted_at = now()
                        WHERE id = ? AND status <> 'DELETED'
                        """,
                AssetStatus.DELETED.name(), id) > 0;
    }

    private static CreativeAssetRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new CreativeAssetRecord(
                UUID.fromString(rs.getString("id")),
                (UUID) rs.getObject("project_id"),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                (UUID) rs.getObject("source_job_id"),
                (Integer) rs.getObject("output_index"),
                AssetOrigin.valueOf(rs.getString("origin")),
                rs.getString("media_type"),
                StorageBackend.valueOf(rs.getString("storage_backend")),
                rs.getString("object_key"),
                rs.getString("mime_type"),
                rs.getLong("byte_size"),
                rs.getString("sha256"),
                (Integer) rs.getObject("width"),
                (Integer) rs.getObject("height"),
                (Long) rs.getObject("duration_ms"),
                rs.getString("display_name"),
                rs.getBoolean("starred"),
                AssetStatus.valueOf(rs.getString("status")),
                rs.getString("metadata"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("deleted_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
