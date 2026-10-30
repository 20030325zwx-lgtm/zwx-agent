package com.zwx.zwxagent.creative.repository;

import com.zwx.zwxagent.creative.domain.MediaJobEventRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * media_job_event 数据访问。append 必须在已锁 job 行的事务内调用（实施手册 §2.2），
 * 事件 seq 由 BIGSERIAL 分配，读取方以 (job_id, seq) 游标增量拉取。
 */
@Repository
public class MediaJobEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public MediaJobEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<MediaJobEventRecord> MAPPER = MediaJobEventRepository::map;

    /** 返回分配的 seq。调用方负责与状态更新同事务提交。 */
    public long append(UUID jobId, String tenantId, String userId, String eventType, String payloadJson) {
        Long seq = jdbcTemplate.queryForObject("""
                        INSERT INTO media_job_event (job_id, tenant_id, user_id, event_type, payload)
                        VALUES (?, ?, ?, ?, ?::jsonb)
                        RETURNING seq
                        """,
                Long.class, jobId, tenantId, userId, eventType, payloadJson);
        if (seq == null) throw new IllegalStateException("事件写入未返回 seq");
        return seq;
    }

    public List<MediaJobEventRecord> listAfter(UUID jobId, long afterSeq, int limit) {
        return jdbcTemplate.query("""
                        SELECT seq, job_id, tenant_id, user_id, event_type, payload, created_at
                        FROM media_job_event WHERE job_id = ? AND seq > ?
                        ORDER BY seq ASC LIMIT ?
                        """,
                MAPPER, jobId, afterSeq, limit);
    }

    private static MediaJobEventRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new MediaJobEventRecord(
                rs.getLong("seq"),
                UUID.fromString(rs.getString("job_id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                toInstant(rs.getTimestamp("created_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
