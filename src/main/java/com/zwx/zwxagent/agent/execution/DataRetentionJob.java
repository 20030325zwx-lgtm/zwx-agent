package com.zwx.zwxagent.agent.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据保留策略（ROADMAP「安全、租户与治理」）：定期清理与瘦身执行痕迹。
 * - agent_execution_event 超过 execution-event-days 删除；
 * - 已完结 run（COMPLETED/FAILED/INTERRUPTED）超过 run-detail-days 清空大字段（事件/活动/计划/结果），
 *   保留 run 元数据、最终回答与 token 台账供成本归因；
 * - agent_tool_call 幂等账本超过 tool-call-days 删除（审计口径更长）。
 * 删除属破坏性操作：默认关闭，由管理员显式开启；ON CONFLICT 无，纯按时间删除。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.retention", name = "enabled", havingValue = "true")
public class DataRetentionJob {

    private final JdbcTemplate jdbcTemplate;
    private final int executionEventDays;
    private final int runDetailDays;
    private final int toolCallDays;

    public DataRetentionJob(JdbcTemplate jdbcTemplate,
                            @Value("${app.retention.execution-event-days:90}") int executionEventDays,
                            @Value("${app.retention.run-detail-days:180}") int runDetailDays,
                            @Value("${app.retention.tool-call-days:365}") int toolCallDays) {
        this.jdbcTemplate = jdbcTemplate;
        this.executionEventDays = Math.max(7, executionEventDays);
        this.runDetailDays = Math.max(30, runDetailDays);
        this.toolCallDays = Math.max(30, toolCallDays);
    }

    /** 每日 03:30 执行；任何一段失败不影响后续段落。 */
    @Scheduled(cron = "${app.retention.cron:0 30 3 * * *}")
    public void run() {
        purgeExecutionEvents();
        slimExpiredRuns();
        purgeToolCalls();
    }

    private void purgeExecutionEvents() {
        try {
            int deleted = jdbcTemplate.update("""
                    DELETE FROM agent_execution_event
                    WHERE created_at < CURRENT_TIMESTAMP - (? || ' days')::interval
                    """, executionEventDays);
            if (deleted > 0) log.info("保留策略：清理 {} 天前的执行事件 {} 条", executionEventDays, deleted);
        } catch (Exception exception) {
            log.warn("保留策略清理执行事件失败：{}", exception.getMessage());
        }
    }

    private void slimExpiredRuns() {
        try {
            int updated = jdbcTemplate.update("""
                    UPDATE agent_run SET
                        sse_events = NULL, activities = NULL, plan = NULL, task_results = NULL,
                        history_context = NULL, knowledge_context = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE status IN ('COMPLETED', 'FAILED', 'INTERRUPTED')
                      AND pending_confirmation IS NULL
                      AND updated_at < CURRENT_TIMESTAMP - (? || ' days')::interval
                      AND sse_events IS NOT NULL
                    """, runDetailDays);
            if (updated > 0) log.info("保留策略：瘦身 {} 天前完结 run 的大字段 {} 条", runDetailDays, updated);
        } catch (Exception exception) {
            log.warn("保留策略瘦身 run 失败：{}", exception.getMessage());
        }
    }

    private void purgeToolCalls() {
        try {
            int deleted = jdbcTemplate.update("""
                    DELETE FROM agent_tool_call
                    WHERE created_at < CURRENT_TIMESTAMP - (? || ' days')::interval
                    """, toolCallDays);
            if (deleted > 0) log.info("保留策略：清理 {} 天前的工具调用账本 {} 条", toolCallDays, deleted);
        } catch (Exception exception) {
            log.warn("保留策略清理工具账本失败：{}", exception.getMessage());
        }
    }
}
