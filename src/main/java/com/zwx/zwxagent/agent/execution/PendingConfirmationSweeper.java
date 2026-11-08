package com.zwx.zwxagent.agent.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具确认超时兜底：待确认请求超过 pending-timeout-minutes 后自动拒绝并恢复 run，
 * 消除「用户永不响应 → run 无限期悬挂」。拒绝是保守方向：恢复后模型收到
 * 「用户已拒绝」反馈，会改用只读方式或如实说明限制收尾。
 * 仅自动恢复 v2 引擎、phase=STOPPED 的挂起 run；与用户手动决定走同一条 A0 闭锁链路，
 * 决定以库为准，竞态下后到者落空，不会双重生效。
 */
@Slf4j
@Component
public class PendingConfirmationSweeper {

    private final ToolConfirmationService toolConfirmationService;
    private final AgentRunStateService agentRunStateService;
    private final JdbcTemplate jdbcTemplate;

    public PendingConfirmationSweeper(ToolConfirmationService toolConfirmationService,
                                      AgentRunStateService agentRunStateService,
                                      JdbcTemplate jdbcTemplate) {
        this.toolConfirmationService = toolConfirmationService;
        this.agentRunStateService = agentRunStateService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void sweep() {
        Set<String> affectedRuns;
        try {
            affectedRuns = toolConfirmationService.expireStalePending();
        } catch (Exception exception) {
            log.warn("工具确认超时清理异常：{}", exception.getMessage());
            return;
        }
        for (String runId : affectedRuns) {
            tryAutoResume(runId);
        }
    }

    /** 超时拒绝后自动恢复：仍 pending → 等；run 非 STOPPED/INTERRUPTED（已终态/已恢复）→ 跳过。 */
    private void tryAutoResume(String runId) {
        try {
            if (toolConfirmationService.hasPending(runId)) return;
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT tenant_id, user_id, phase, status FROM agent_run WHERE id = ?", runId);
            if (rows.isEmpty()) return;
            Map<String, Object> run = rows.get(0);
            String phase = String.valueOf(run.get("phase"));
            String status = String.valueOf(run.get("status"));
            if (!"STOPPED".equals(phase) || !"INTERRUPTED".equals(status)) return;
            boolean resumed = agentRunStateService.resume(runId,
                    String.valueOf(run.get("tenant_id")), String.valueOf(run.get("user_id")));
            log.info("超时拒绝后自动恢复 run；runId={}, resumed={}", runId, resumed);
        } catch (Exception exception) {
            log.warn("超时拒绝后自动恢复失败；runId={}", runId, exception);
        }
    }
}
