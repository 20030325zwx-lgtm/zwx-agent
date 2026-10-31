package com.zwx.zwxagent.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * C13：工作流媒体等待恢复器。按 media_job 终态推进等待中的节点；
 * 成功写结构化输出并唤醒后继，失败把节点与运行标记失败。
 * 认领由 workflow_run 状态条件更新保证，多个实例并发时只有一个推进。
 */
@Component
public class WorkflowMediaResumeScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowMediaResumeScheduler.class);

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowService workflowService;
    private final boolean enabled;
    private final int batchSize;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorkflowMediaResumeScheduler(JdbcTemplate jdbcTemplate, WorkflowService workflowService,
                                        @Value("${app.workflow.media-resume.enabled:true}") boolean enabled,
                                        @Value("${app.workflow.media-resume.batch-size:20}") int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.workflowService = workflowService;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${app.workflow.media-resume.interval-ms:5000}")
    public void tick() {
        if (!enabled) return;
        // R02（评审 2026-10-31）：SQL 层先筛媒体已终态的等待节点，避免"前 20 个长期生成
        // 任务"把已完成任务堵在扫描之外（队首阻塞）；仍在生成的节点不进入本轮结果。
        List<Map<String, Object>> ready = jdbcTemplate.queryForList("""
                SELECT e.run_id, e.node_key, e.media_job_id, j.status AS job_status
                FROM workflow_node_execution e
                JOIN media_job j ON j.id = e.media_job_id
                WHERE e.status = 'WAITING_EXTERNAL'
                  AND j.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                ORDER BY j.finished_at NULLS LAST, e.updated_at
                LIMIT ?
                """, batchSize);
        for (var row : ready) {
            try {
                handle(row);
            } catch (Exception exception) {
                log.warn("工作流媒体恢复失败：run={} node={}；{}", row.get("run_id"), row.get("node_key"),
                        exception.getMessage());
            }
        }
        repairStuckRuns();
    }

    /**
     * R01（评审 2026-10-31）崩溃窗口修复：
     * ① 媒体任务已创建但节点等待关系未落库（孤儿任务）→ 按 source_run_id/node_id 重链；
     * ② 节点已在等待但运行仍是 RUNNING（markWaiting 前崩溃 / 恢复翻转后被中断）→ 把运行翻回 WAITING_EXTERNAL，
     *    让 resumeWaiting 认领续跑（媒体节点幂等键复用既有任务，不会重复计费）。
     */
    private void repairStuckRuns() {
        try {
            jdbcTemplate.update("""
                    INSERT INTO workflow_node_execution (run_id, node_key, status, media_job_id, attempt)
                    SELECT j.source_run_id, j.source_node_id, 'WAITING_EXTERNAL', j.id, 1
                    FROM media_job j
                    WHERE j.source_type = 'WORKFLOW' AND j.source_run_id IS NOT NULL AND j.source_node_id IS NOT NULL
                      AND j.status IN ('QUEUED', 'SUBMITTING', 'RECONCILING', 'RUNNING', 'CANCEL_REQUESTED',
                                       'FETCHING', 'INGEST_FAILED', 'SUCCEEDED')
                      AND NOT EXISTS (
                          SELECT 1 FROM workflow_node_execution e
                          WHERE e.run_id = j.source_run_id AND e.node_key = j.source_node_id)
                      AND EXISTS (
                          SELECT 1 FROM workflow_run r
                          WHERE r.id = j.source_run_id AND r.status IN ('RUNNING', 'WAITING_EXTERNAL'))
                    LIMIT ?
                    """, Math.max(1, batchSize));
            jdbcTemplate.update("""
                    UPDATE workflow_run r SET status = 'WAITING_EXTERNAL', updated_at = CURRENT_TIMESTAMP
                    WHERE r.status = 'RUNNING'
                      AND EXISTS (
                          SELECT 1 FROM workflow_node_execution e
                          WHERE e.run_id = r.id AND e.status = 'WAITING_EXTERNAL')
                    """);
        } catch (Exception exception) {
            log.warn("工作流等待修复失败：{}", exception.getMessage());
        }
    }

    private void handle(Map<String, Object> row) {
        String runId = String.valueOf(row.get("run_id"));
        String nodeKey = String.valueOf(row.get("node_key"));
        String jobId = String.valueOf(row.get("media_job_id"));
        String status = String.valueOf(row.get("job_status"));
        switch (status) {
            case "SUCCEEDED" -> {
                List<Map<String, Object>> assets = jdbcTemplate.queryForList("""
                        SELECT asset_id FROM media_job_output
                        WHERE job_id = ? AND ingest_state = 'DONE'
                        ORDER BY output_index LIMIT 1
                        """, java.util.UUID.fromString(jobId));
                if (assets.isEmpty()) {
                    workflowService.failMediaNode(runId, nodeKey, "图片任务已完成但没有可用的入库产物");
                    return;
                }
                String assetId = String.valueOf(assets.getFirst().get("asset_id"));
                String output = writeJson(Map.of(
                        "assetId", assetId,
                        "jobId", jobId,
                        "mediaType", "IMAGE"));
                workflowService.advanceMediaNode(runId, nodeKey, output, "图片已生成并保存到素材库");
            }
            case "FAILED" -> workflowService.failMediaNode(runId, nodeKey, mediaError(jobId));
            case "CANCELLED" -> workflowService.failMediaNode(runId, nodeKey, "图片任务已取消");
            default -> { /* 仍在生成/入库失败重试：保持等待，不推进 */ }
        }
    }

    private String mediaError(String jobId) {
        List<String> errors = jdbcTemplate.queryForList("""
                SELECT COALESCE(error_summary, error_code, '图片生成失败')
                FROM media_job WHERE id = ?
                """, String.class, java.util.UUID.fromString(jobId));
        return errors.isEmpty() ? "图片生成失败" : errors.getFirst();
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("媒体节点输出序列化失败", exception);
        }
    }
}
