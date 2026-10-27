package com.zwx.zwxagent.platform.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** DIRECT 模式的数据库单写者租约；进程退出后由过期时间自动失效。 */
@Service
public class ProjectExecutionLockService {

    private final JdbcTemplate jdbcTemplate;
    private final int leaseMinutes;

    public ProjectExecutionLockService(JdbcTemplate jdbcTemplate,
                                       @Value("${app.project-workspace.write-lease-minutes:30}") int leaseMinutes) {
        this.jdbcTemplate = jdbcTemplate;
        this.leaseMinutes = Math.max(1, leaseMinutes);
    }

    public void acquire(String projectId, String runId) {
        List<String> acquired = jdbcTemplate.queryForList("""
                INSERT INTO agent_project_write_lock (project_id, run_id, expires_at)
                VALUES (?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 minute'))
                ON CONFLICT (project_id) DO UPDATE SET
                    run_id = EXCLUDED.run_id,
                    expires_at = EXCLUDED.expires_at,
                    updated_at = CURRENT_TIMESTAMP
                WHERE agent_project_write_lock.run_id = EXCLUDED.run_id
                   OR agent_project_write_lock.expires_at < CURRENT_TIMESTAMP
                RETURNING project_id
                """, String.class, projectId, runId, leaseMinutes);
        if (acquired.isEmpty()) throw new ProjectWorkspaceConflictException("该项目正被另一个任务修改，请稍后重试或使用 worktree");
    }

    public void release(String runId) {
        if (runId == null || runId.isBlank()) return;
        jdbcTemplate.update("DELETE FROM agent_project_write_lock WHERE run_id = ?", runId);
    }
}
