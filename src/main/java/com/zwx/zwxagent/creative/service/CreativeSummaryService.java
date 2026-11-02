package com.zwx.zwxagent.creative.service;

import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 跨项目"待处理"聚合（UI3）：首页与任务中心只关心"现在需要我处理什么"，
 * 不关心某个项目里的完整任务列表，所以这里给一份轻量汇总，而不是让前端拉全量再过滤。
 *
 * 三类数字来源不同：
 * - 执行状态来自 media_job（进行中 / 失败 / 保存失败 / 结果待确认）；
 * - 采用状态来自 video_shot（已生成待选片的镜头）。
 */
@Service
public class CreativeSummaryService {

    private final JdbcTemplate jdbcTemplate;

    public CreativeSummaryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Item(UUID jobId, UUID projectId, String projectName, String operation, String status,
                       String prompt, Instant createdAt) {
    }

    public record Summary(int active, int failed, int ingestFailed, int reconciling, int pendingSelection,
                          List<Item> items) {
    }

    public Summary summary(CurrentActor actor) {
        String tenantId = actor.tenantId();
        String userId = actor.userId();
        Integer active = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM media_job
                WHERE tenant_id = ? AND user_id = ?
                  AND status IN ('QUEUED','SUBMITTING','RUNNING','RECONCILING','CANCEL_REQUESTED','FETCHING')
                """, Integer.class, tenantId, userId);
        Integer failed = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM media_job
                WHERE tenant_id = ? AND user_id = ? AND status = 'FAILED'
                """, Integer.class, tenantId, userId);
        Integer ingestFailed = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM media_job
                WHERE tenant_id = ? AND user_id = ? AND status = 'INGEST_FAILED'
                """, Integer.class, tenantId, userId);
        Integer reconciling = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM media_job
                WHERE tenant_id = ? AND user_id = ? AND status = 'RECONCILING'
                """, Integer.class, tenantId, userId);
        Integer pendingSelection = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM video_shot
                WHERE tenant_id = ? AND user_id = ? AND status = 'PENDING_SELECTION'
                """, Integer.class, tenantId, userId);

        List<Item> items = jdbcTemplate.query("""
                        SELECT j.id, j.project_id, p.name AS project_name, j.operation, j.status,
                               j.input_snapshot ->> 'prompt' AS prompt, j.created_at
                        FROM media_job j
                        LEFT JOIN creative_project p ON p.id = j.project_id
                        WHERE j.tenant_id = ? AND j.user_id = ?
                          AND j.status IN ('FAILED','INGEST_FAILED','RECONCILING')
                        ORDER BY j.updated_at DESC, j.id DESC
                        LIMIT 10
                        """, (rs, rowNum) -> new Item(
                        UUID.fromString(rs.getString("id")),
                        (UUID) rs.getObject("project_id"),
                        rs.getString("project_name"),
                        rs.getString("operation"),
                        rs.getString("status"),
                        rs.getString("prompt"),
                        rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()),
                tenantId, userId);

        return new Summary(nz(active), nz(failed), nz(ingestFailed), nz(reconciling), nz(pendingSelection), items);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
