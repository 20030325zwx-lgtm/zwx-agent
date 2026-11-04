package com.zwx.aster.data.repository;

import com.zwx.aster.data.domain.DataJson;
import com.zwx.aster.data.domain.UserFeedbackRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 反馈仓储：插入（一次点击）、管理侧过滤列表、守卫状态流转。 */
@Repository
public class UserFeedbackRepository {

    private static final String SELECT = """
            SELECT id, tenant_id, user_id, rating, source, status, reason, tags,
                   refs::text AS refs, converted_dataset_id, converted_case_id, created_at, updated_at
            FROM platform.user_feedback
            """;

    private static final RowMapper<UserFeedbackRecord> MAPPER = (rs, rowNum) -> new UserFeedbackRecord(
            UUID.fromString(rs.getString("id")),
            rs.getString("tenant_id"),
            rs.getString("user_id"),
            rs.getString("rating"),
            rs.getString("source"),
            rs.getString("status"),
            rs.getString("reason"),
            List.of((String[]) rs.getArray("tags").getArray()),
            DataJson.parseMap(rs.getString("refs")),
            rs.getString("converted_dataset_id") == null ? null : UUID.fromString(rs.getString("converted_dataset_id")),
            rs.getString("converted_case_id") == null ? null : UUID.fromString(rs.getString("converted_case_id")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public UserFeedbackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UserFeedbackRecord insert(UserFeedbackRecord record) {
        jdbcTemplate.update("""
                        INSERT INTO platform.user_feedback (id, tenant_id, user_id, rating, source, status,
                                                            reason, tags, refs)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                        """,
                record.id(), record.tenantId(), record.userId(), record.rating(), record.source(),
                record.status(), record.reason(), record.tags().toArray(new String[0]),
                DataJson.toJson(record.refs()));
        return findById(record.id()).orElseThrow();
    }

    public Optional<UserFeedbackRecord> findById(UUID id) {
        return jdbcTemplate.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<UserFeedbackRecord> list(String tenantId, String status, String rating, String source,
                                         Long cursorMillis, String cursorId, int limit) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE tenant_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (rating != null && !rating.isBlank()) {
            sql.append(" AND rating = ?");
            args.add(rating);
        }
        if (source != null && !source.isBlank()) {
            sql.append(" AND source = ?");
            args.add(source);
        }
        if (cursorMillis != null && cursorId != null && !cursorId.isBlank()) {
            sql.append(" AND (created_at, id) < (to_timestamp(? / 1000.0), ?)");
            args.add(cursorMillis);
            args.add(cursorId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    public boolean transitionGuarded(UUID id, String targetStatus) {
        return jdbcTemplate.update("""
                        UPDATE platform.user_feedback SET status = ?, updated_at = now()
                        WHERE id = ? AND status IN ('open', 'acked')
                        """, targetStatus, id) > 0;
    }

    public boolean markConverted(UUID id, UUID datasetId, UUID caseId) {
        return jdbcTemplate.update("""
                        UPDATE platform.user_feedback
                        SET status = 'converted', converted_dataset_id = ?, converted_case_id = ?, updated_at = now()
                        WHERE id = ? AND status IN ('open', 'acked')
                        """, datasetId, caseId, id) > 0;
    }

    public Map<String, Long> counts(String tenantId) {
        return jdbcTemplate.query("""
                        SELECT status, rating, COUNT(*) AS n FROM platform.user_feedback
                        WHERE tenant_id = ? GROUP BY status, rating
                        """,
                rs -> {
                    java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        out.merge(rs.getString("status"), rs.getLong("n"), Long::sum);
                        out.merge("rating_" + rs.getString("rating"), rs.getLong("n"), Long::sum);
                    }
                    return out;
                }, tenantId);
    }
}
