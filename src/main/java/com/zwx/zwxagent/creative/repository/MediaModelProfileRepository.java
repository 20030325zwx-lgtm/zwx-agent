package com.zwx.zwxagent.creative.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zwx.zwxagent.creative.domain.VerificationStatus;
import com.zwx.zwxagent.creative.support.CreativeJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** media_model_profile 数据访问：每个模型行至多一份能力档案。 */
@Repository
public class MediaModelProfileRepository {

    public record MediaModelProfileRecord(UUID id, long modelRowId, String adapterKey, String adapterVersion,
                                          Map<String, Boolean> operations, Map<String, Object> parameterSchemaJson,
                                          Map<String, Object> limits, VerificationStatus verificationStatus,
                                          Instant verifiedAt, Map<String, Object> priceMetadata, long revision,
                                          Instant createdAt, Instant updatedAt) {
    }

    private final JdbcTemplate jdbcTemplate;

    public MediaModelProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<MediaModelProfileRecord> MAPPER = MediaModelProfileRepository::map;

    private static final String SELECT = """
            SELECT id, model_row_id, adapter_key, adapter_version, operations, parameter_schema, limits,
                   verification_status, verified_at, price_metadata, revision, created_at, updated_at
            FROM media_model_profile
            """;

    public Optional<MediaModelProfileRecord> findByModelRowId(long modelRowId) {
        return jdbcTemplate.query(SELECT + " WHERE model_row_id = ?", MAPPER, modelRowId).stream().findFirst();
    }

    public List<MediaModelProfileRecord> listByAdapter(String adapterKey) {
        return jdbcTemplate.query(SELECT + " WHERE adapter_key = ?", MAPPER, adapterKey);
    }

    /** 幂等 upsert：同一模型行的档案只保留一份，revision 随内容更新递增。 */
    public void upsert(long modelRowId, String adapterKey, String adapterVersion,
                       Map<String, Boolean> operations, Map<String, Object> parameterSchemaJson,
                       VerificationStatus verificationStatus, Instant verifiedAt) {
        jdbcTemplate.update("""
                        INSERT INTO media_model_profile
                            (id, model_row_id, adapter_key, adapter_version, operations, parameter_schema,
                             verification_status, verified_at)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                        ON CONFLICT (model_row_id) DO UPDATE SET
                            adapter_key = EXCLUDED.adapter_key,
                            adapter_version = EXCLUDED.adapter_version,
                            operations = EXCLUDED.operations,
                            parameter_schema = EXCLUDED.parameter_schema,
                            verification_status = EXCLUDED.verification_status,
                            verified_at = EXCLUDED.verified_at,
                            revision = media_model_profile.revision + 1,
                            updated_at = now()
                        """,
                ps -> {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setLong(2, modelRowId);
                    ps.setString(3, adapterKey);
                    ps.setString(4, adapterVersion);
                    ps.setString(5, CreativeJson.toJson(operations));
                    ps.setString(6, CreativeJson.toJson(parameterSchemaJson));
                    ps.setString(7, verificationStatus == null
                            ? VerificationStatus.UNVERIFIED.name() : verificationStatus.name());
                    if (verifiedAt == null) ps.setNull(8, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                    else ps.setTimestamp(8, Timestamp.from(verifiedAt));
                });
    }

    private static MediaModelProfileRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new MediaModelProfileRecord(
                UUID.fromString(rs.getString("id")),
                rs.getLong("model_row_id"),
                rs.getString("adapter_key"),
                rs.getString("adapter_version"),
                CreativeJson.fromJson(rs.getString("operations"), new TypeReference<Map<String, Boolean>>() { }),
                CreativeJson.fromJson(rs.getString("parameter_schema"), new TypeReference<Map<String, Object>>() { }),
                CreativeJson.fromJson(rs.getString("limits"), new TypeReference<Map<String, Object>>() { }),
                VerificationStatus.valueOf(rs.getString("verification_status")),
                toInstant(rs.getTimestamp("verified_at")),
                CreativeJson.fromJson(rs.getString("price_metadata"), new TypeReference<Map<String, Object>>() { }),
                rs.getLong("revision"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
