package com.zwx.zwxagent.creative.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** media_provider_quota 数据访问：GLOBAL/PROVIDER/USER 三层配额（实施手册 C05）。 */
@Repository
public class MediaProviderQuotaRepository {

    private final JdbcTemplate jdbcTemplate;

    public MediaProviderQuotaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Integer> findMaxQueued(String scopeType, String scopeId) {
        return jdbcTemplate.query("""
                        SELECT max_queued FROM media_provider_quota WHERE scope_type = ? AND scope_id = ?
                        """,
                (rs, rowNum) -> rs.getObject("max_queued", Integer.class), scopeType, scopeId)
                .stream().findFirst();
    }

    public Optional<Integer> findMaxInflight(String scopeType, String scopeId) {
        return jdbcTemplate.query("""
                        SELECT max_inflight FROM media_provider_quota WHERE scope_type = ? AND scope_id = ?
                        """,
                (rs, rowNum) -> rs.getInt("max_inflight"), scopeType, scopeId)
                .stream().findFirst();
    }

    public void upsert(String scopeType, String scopeId, int maxInflight, Integer maxQueued) {
        jdbcTemplate.update("""
                        INSERT INTO media_provider_quota (scope_type, scope_id, max_inflight, max_queued)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (scope_type, scope_id) DO UPDATE SET
                            max_inflight = EXCLUDED.max_inflight, max_queued = EXCLUDED.max_queued,
                            updated_at = now()
                        """,
                scopeType, scopeId, maxInflight, maxQueued);
    }

    public void delete(String scopeType, String scopeId) {
        jdbcTemplate.update("DELETE FROM media_provider_quota WHERE scope_type = ? AND scope_id = ?",
                scopeType, scopeId);
    }
}
