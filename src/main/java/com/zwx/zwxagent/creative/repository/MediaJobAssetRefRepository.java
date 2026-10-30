package com.zwx.zwxagent.creative.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * media_job_asset_ref 数据访问：任务与素材引用的事实来源，
 * “素材是否被活动任务在用”必须查这里，不能只看 input_snapshot JSON（实施手册 §2.2）。
 */
@Repository
public class MediaJobAssetRefRepository {

    private final JdbcTemplate jdbcTemplate;

    public MediaJobAssetRefRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void addRef(UUID jobId, UUID assetId, String role, String tenantId, String userId) {
        jdbcTemplate.update("""
                        INSERT INTO media_job_asset_ref (job_id, asset_id, role, tenant_id, user_id)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (job_id, asset_id, role) DO NOTHING
                        """,
                jobId, assetId, role, tenantId, userId);
    }

    /** 素材被未终态任务引用的数量（BR14：删除素材时 >0 返回 409）。 */
    public int countActiveJobRefs(UUID assetId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM media_job_asset_ref r
                        JOIN media_job j ON j.id = r.job_id
                        WHERE r.asset_id = ? AND j.status IN
                            ('QUEUED','SUBMITTING','RECONCILING','RUNNING','CANCEL_REQUESTED','FETCHING','INGEST_FAILED')
                        """,
                Integer.class, assetId);
        return count == null ? 0 : count;
    }
}
