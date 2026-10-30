package com.zwx.zwxagent.creative.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zwx.zwxagent.creative.domain.CreativeDraftRecord;
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

/** creative_draft 数据访问。更新必须带 expectedVersion（BR18），冲突由调用方映射 409。 */
@Repository
public class CreativeDraftRepository {

    private final JdbcTemplate jdbcTemplate;

    public CreativeDraftRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<CreativeDraftRecord> MAPPER = CreativeDraftRepository::map;

    public void insert(CreativeDraftRecord draft) {
        jdbcTemplate.update("""
                        INSERT INTO creative_draft
                            (id, project_id, tenant_id, user_id, operation, original_prompt, effective_prompt,
                             reference_asset_ids, model_row_id, parameters, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?)
                        """,
                ps -> {
                    ps.setObject(1, draft.id());
                    ps.setObject(2, draft.projectId());
                    ps.setString(3, draft.tenantId());
                    ps.setString(4, draft.userId());
                    ps.setString(5, draft.operation());
                    ps.setString(6, draft.originalPrompt());
                    ps.setString(7, draft.effectivePrompt());
                    ps.setString(8, CreativeJson.toJson(draft.referenceAssetIds()));
                    if (draft.modelRowId() == null) ps.setNull(9, java.sql.Types.BIGINT);
                    else ps.setLong(9, draft.modelRowId());
                    ps.setString(10, CreativeJson.toJson(draft.parameters()));
                    ps.setLong(11, draft.version());
                });
    }

    public Optional<CreativeDraftRecord> findByIdForOwner(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ? AND tenant_id = ? AND user_id = ?",
                MAPPER, id, tenantId, userId).stream().findFirst();
    }

    public List<CreativeDraftRecord> listByProject(String tenantId, String userId, UUID projectId, int limit) {
        return jdbcTemplate.query(SELECT + """
                        WHERE tenant_id = ? AND user_id = ? AND project_id = ?
                        ORDER BY updated_at DESC, id DESC LIMIT ?
                        """,
                MAPPER, tenantId, userId, projectId, limit);
    }

    /**
     * 乐观锁更新：expectedVersion 不匹配返回 false（调用方映射 409），不自动覆盖服务器文本（BR18）。
     */
    public boolean updateContent(UUID id, String tenantId, String userId, long expectedVersion,
                                 String operation, String originalPrompt, String effectivePrompt,
                                 List<UUID> referenceAssetIds, Long modelRowId, java.util.Map<String, Object> parameters) {
        return jdbcTemplate.update("""
                        UPDATE creative_draft
                        SET operation = ?, original_prompt = ?, effective_prompt = ?,
                            reference_asset_ids = ?::jsonb, model_row_id = ?, parameters = ?::jsonb,
                            version = version + 1, updated_at = now()
                        WHERE id = ? AND tenant_id = ? AND user_id = ? AND version = ?
                        """,
                ps -> {
                    ps.setString(1, operation);
                    ps.setString(2, originalPrompt);
                    ps.setString(3, effectivePrompt);
                    ps.setString(4, CreativeJson.toJson(referenceAssetIds));
                    if (modelRowId == null) ps.setNull(5, java.sql.Types.BIGINT);
                    else ps.setLong(5, modelRowId);
                    ps.setString(6, CreativeJson.toJson(parameters));
                    ps.setObject(7, id);
                    ps.setString(8, tenantId);
                    ps.setString(9, userId);
                    ps.setLong(10, expectedVersion);
                }) > 0;
    }

    private static CreativeDraftRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new CreativeDraftRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("operation"),
                rs.getString("original_prompt"),
                rs.getString("effective_prompt"),
                CreativeJson.fromJson(rs.getString("reference_asset_ids"), new TypeReference<List<UUID>>() { }),
                (Long) rs.getObject("model_row_id"),
                CreativeJson.fromJson(rs.getString("parameters"), new TypeReference<java.util.Map<String, Object>>() { }),
                rs.getLong("version"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static final String SELECT = """
            SELECT id, project_id, tenant_id, user_id, operation, original_prompt, effective_prompt,
                   reference_asset_ids, model_row_id, parameters, version, created_at, updated_at
            FROM creative_draft
            """;

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
