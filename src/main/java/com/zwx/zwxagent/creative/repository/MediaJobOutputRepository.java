package com.zwx.zwxagent.creative.repository;

import com.zwx.zwxagent.creative.domain.IngestState;
import com.zwx.zwxagent.creative.domain.MediaJobOutputRecord;
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

/** media_job_output 数据访问：产物槽位在进入 FETCHING 前预分配，键不再随机生成（实施手册 §2.2）。 */
@Repository
public class MediaJobOutputRepository {

    private final JdbcTemplate jdbcTemplate;

    public MediaJobOutputRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<MediaJobOutputRecord> MAPPER = MediaJobOutputRepository::map;

    public void insert(MediaJobOutputRecord output) {
        jdbcTemplate.update("""
                        INSERT INTO media_job_output
                            (id, job_id, output_index, asset_id, ingest_state, storage_backend, object_key)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, output.id());
                    ps.setObject(2, output.jobId());
                    ps.setInt(3, output.outputIndex());
                    ps.setObject(4, output.assetId());
                    ps.setString(5, output.ingestState().name());
                    ps.setString(6, output.storageBackend().name());
                    ps.setString(7, output.objectKey());
                });
    }

    public List<MediaJobOutputRecord> findByJob(UUID jobId) {
        return jdbcTemplate.query("""
                        SELECT id, job_id, output_index, asset_id, ingest_state, storage_backend,
                               object_key, sha256, byte_size, last_error, created_at, updated_at
                        FROM media_job_output WHERE job_id = ? ORDER BY output_index
                        """,
                MAPPER, jobId);
    }

    public Optional<MediaJobOutputRecord> findByAsset(UUID assetId) {
        return jdbcTemplate.query("""
                        SELECT id, job_id, output_index, asset_id, ingest_state, storage_backend,
                               object_key, sha256, byte_size, last_error, created_at, updated_at
                        FROM media_job_output WHERE asset_id = ?
                        """,
                MAPPER, assetId).stream().findFirst();
    }

    /** 入库完成：对象已写、校验通过后调用（A19/A20）。 */
    public boolean markDone(UUID id, String sha256, long byteSize) {
        return jdbcTemplate.update("""
                        UPDATE media_job_output SET ingest_state = ?, sha256 = ?, byte_size = ?, updated_at = now()
                        WHERE id = ?
                        """,
                IngestState.DONE.name(), sha256, byteSize, id) > 0;
    }

    /** 入库失败：保留槽位与键，等待 recover-result 重试（不重新生成）。 */
    public boolean markFailed(UUID id, String lastError) {
        return jdbcTemplate.update("""
                        UPDATE media_job_output SET ingest_state = ?, last_error = ?, updated_at = now()
                        WHERE id = ?
                        """,
                IngestState.FAILED.name(), lastError, id) > 0;
    }

    /** 恢复为待取（存储恢复后重新 FETCH）。 */
    public boolean markPending(UUID id) {
        return jdbcTemplate.update("""
                        UPDATE media_job_output SET ingest_state = ?, last_error = NULL, updated_at = now()
                        WHERE id = ?
                        """,
                IngestState.PENDING.name(), id) > 0;
    }

    private static MediaJobOutputRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new MediaJobOutputRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("job_id")),
                rs.getInt("output_index"),
                UUID.fromString(rs.getString("asset_id")),
                IngestState.valueOf(rs.getString("ingest_state")),
                StorageBackend.valueOf(rs.getString("storage_backend")),
                rs.getString("object_key"),
                rs.getString("sha256"),
                (Long) rs.getObject("byte_size"),
                rs.getString("last_error"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
