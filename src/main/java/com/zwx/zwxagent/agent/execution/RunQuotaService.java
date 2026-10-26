package com.zwx.zwxagent.agent.execution;

import com.zwx.zwxagent.agent.observability.AgentMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 每用户运行配额：并发 run 上限、每小时新建 run 数、24 小时 token 预算。
 * 在新建/恢复 run 前检查，超限抛 429，防止单用户无界消耗模型成本。
 * 配额口径只看 agent_run 表（含崩溃后由恢复任务标记 INTERRUPTED 的 run，短暂高估可接受）。
 */
@Slf4j
@Service
public class RunQuotaService {

    private final boolean enabled;
    private final int maxConcurrentRuns;
    private final int hourlyRuns;
    private final long dailyTokenBudget;
    private final JdbcTemplate jdbcTemplate;
    private final AgentMetrics metrics;

    public RunQuotaService(@Value("${app.run.quota.enabled:true}") boolean enabled,
                           @Value("${app.run.quota.max-concurrent-runs:2}") int maxConcurrentRuns,
                           @Value("${app.run.quota.hourly-runs:30}") int hourlyRuns,
                           @Value("${app.run.quota.daily-token-budget:2000000}") long dailyTokenBudget,
                           JdbcTemplate jdbcTemplate,
                           AgentMetrics metrics) {
        this.enabled = enabled;
        this.maxConcurrentRuns = Math.max(1, maxConcurrentRuns);
        this.hourlyRuns = Math.max(1, hourlyRuns);
        this.dailyTokenBudget = Math.max(0, dailyTokenBudget);
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
    }

    /** 新建/恢复 run 前调用；超限抛 429，消息面向用户说明原因。 */
    public void checkRunAllowed(String userId) {
        if (!enabled || userId == null || userId.isBlank()) return;
        Integer running = queryInt("""
                SELECT COUNT(*) FROM agent_run WHERE user_id = ? AND status = 'RUNNING'
                """, userId);
        if (running != null && running >= maxConcurrentRuns) {
            reject("concurrent", "当前有 " + running + " 个任务正在执行，已达到并发上限（" + maxConcurrentRuns
                    + "）。请等待任务完成或先停止不需要的任务。");
        }
        Integer hourly = queryInt("""
                SELECT COUNT(*) FROM agent_run
                WHERE user_id = ? AND created_at > CURRENT_TIMESTAMP - INTERVAL '1 hour'
                """, userId);
        if (hourly != null && hourly >= hourlyRuns) {
            reject("hourly", "最近一小时已发起 " + hourly + " 个任务，达到频率上限（" + hourlyRuns + "），请稍后再试。");
        }
        if (dailyTokenBudget > 0) {
            Long tokens = jdbcTemplate.query("""
                            SELECT COALESCE(SUM(COALESCE(prompt_tokens, 0) + COALESCE(completion_tokens, 0)), 0)
                            FROM agent_run
                            WHERE user_id = ? AND created_at > CURRENT_TIMESTAMP - INTERVAL '24 hours'
                            """, (rs, rowNum) -> rs.getLong(1), userId)
                    .stream().findFirst().orElse(0L);
            if (tokens != null && tokens >= dailyTokenBudget) {
                reject("token-budget", "最近 24 小时的模型用量（约 " + tokens + " tokens）已达到预算上限（" + dailyTokenBudget
                        + "），请联系管理员调整或明天再试。");
            }
        }
    }

    private Integer queryInt(String sql, String userId) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, userId);
        return value == null ? 0 : value;
    }

    private void reject(String kind, String userMessage) {
        if (metrics != null) metrics.quotaRejected(kind);
        log.info("run 配额拒绝；kind={}", kind);
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, userMessage);
    }
}
