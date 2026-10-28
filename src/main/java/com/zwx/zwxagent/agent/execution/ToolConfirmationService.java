package com.zwx.zwxagent.agent.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 有副作用工具的人工确认服务：
 * - 只有显式 registerRun 的 run 才启用确认（图编排路径）；单智能体与无 run 生命周期的调用不受影响；
 * - 请求确认落库（agent_tool_confirmation），跨重启续跑时按 runId 恢复已批准/已拒绝集合；
 * - 决定前 run 以 INTERRUPTED + pending_confirmation 挂起，批准后走既有 resume 链路重新执行。
 * 是否需要确认由 {@code PermissionService} 按权限模式判定；本服务只负责登记、持久化与放行/拒绝状态。
 */
@Slf4j
@Service
public class ToolConfirmationService {

    public static final String DECISION_APPROVED = "APPROVED";
    public static final String DECISION_DENIED = "DENIED";

    private final boolean enabled;
    private final JdbcTemplate jdbcTemplate;
    private final com.zwx.zwxagent.agent.observability.AgentMetrics metrics;

    private final ConcurrentHashMap<String, RunScope> scopes = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ToolConfirmationService(@Value("${app.tools.confirmation.enabled:true}") boolean enabled,
                                   JdbcTemplate jdbcTemplate) {
        this(enabled, jdbcTemplate, null);
    }

    public ToolConfirmationService(boolean enabled, JdbcTemplate jdbcTemplate,
                                   com.zwx.zwxagent.agent.observability.AgentMetrics metrics) {
        this.enabled = enabled;
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 图编排器在每个 run 开始时注册：未注册的 run（单智能体、旧路径）不启用确认。 */
    public void registerRun(String runId) {
        if (!enabled || runId == null || runId.isBlank()) return;
        RunScope scope = scopes.computeIfAbsent(runId, ignored -> new RunScope());
        try {
            jdbcTemplate.query("""
                    SELECT tool_name, decision, arguments_summary FROM agent_tool_confirmation
                    WHERE run_id = ?
                    """, rs -> {
                String toolName = rs.getString("tool_name");
                String decision = rs.getString("decision");
                if (DECISION_APPROVED.equals(decision)) {
                    scope.approved.add(toolName);
                } else if (DECISION_DENIED.equals(decision)) {
                    scope.denied.add(toolName);
                } else {
                    scope.pending.addIfAbsent(new PendingConfirmation(toolName, rs.getString("arguments_summary"), Instant.now()));
                }
            }, runId);
        } catch (Exception exception) {
            log.warn("恢复工具确认状态失败；runId={}", runId, exception);
        }
    }

    public void releaseRun(String runId) {
        if (runId != null) scopes.remove(runId);
    }

    public boolean isRunRegistered(String runId) {
        return runId != null && scopes.containsKey(runId);
    }

    public boolean isApproved(String runId, String toolName) {
        RunScope scope = scopes.get(runId);
        return scope != null && scope.approved.contains(toolName);
    }

    public boolean isDenied(String runId, String toolName) {
        RunScope scope = scopes.get(runId);
        return scope != null && scope.denied.contains(toolName);
    }

    /** 登记一条待确认请求（同一工具幂等）；返回值为 null 表示此前已登记过。 */
    public PendingConfirmation markPending(String runId, String toolName, String argumentsSummary) {
        RunScope scope = scopes.get(runId);
        if (scope == null) return null;
        try {
            jdbcTemplate.update("""
                    INSERT INTO agent_tool_confirmation (run_id, tool_name, arguments_summary, decision)
                    VALUES (?, ?, ?, 'PENDING')
                    ON CONFLICT (run_id, tool_name) DO NOTHING
                    """, runId, toolName, argumentsSummary);
        } catch (Exception exception) {
            log.warn("登记工具确认请求失败；runId={}, tool={}", runId, toolName, exception);
        }
        boolean first = scope.pending.stream().noneMatch(pending -> pending.toolName().equals(toolName));
        if (!first) return null;
        PendingConfirmation record = new PendingConfirmation(toolName, argumentsSummary, Instant.now());
        scope.pending.add(record);
        return record;
    }

    public List<PendingConfirmation> pendingOf(String runId) {
        RunScope scope = scopes.get(runId);
        return scope == null ? List.of() : List.copyOf(scope.pending);
    }

    /**
     * 记录用户决定并持久化；toolName 为空时对全部待确认工具生效。
     *
     * @return 实际更新的工具数
     */
    public int decide(String runId, String toolName, String decision) {
        boolean approved = DECISION_APPROVED.equals(decision);
        if (!approved && !DECISION_DENIED.equals(decision)) {
            throw new IllegalArgumentException("无效的确认决定：" + decision);
        }
        RunScope scope = scopes.get(runId);
        int updated;
        try {
            updated = jdbcTemplate.update("""
                    UPDATE agent_tool_confirmation SET decision = ?, decided_at = CURRENT_TIMESTAMP
                    WHERE run_id = ? AND decision = 'PENDING' AND (?::text IS NULL OR tool_name = ?)
                    """, decision, runId, toolName, toolName);
        } catch (Exception exception) {
            log.warn("持久化工具确认决定失败；runId={}, tool={}", runId, toolName, exception);
            updated = 0;
        }
        if (scope != null) {
            for (PendingConfirmation pending : scope.pending) {
                if (toolName == null || toolName.equals(pending.toolName())) {
                    scope.pending.remove(pending);
                    if (approved) scope.approved.add(pending.toolName());
                    else scope.denied.add(pending.toolName());
                }
            }
        }
        log.info("工具确认决定已记录；runId={}, tool={}, decision={}, updated={}", runId, toolName, decision, updated);
        if (metrics != null) metrics.toolConfirmationDecided(decision);
        return updated;
    }

    public record PendingConfirmation(String toolName, String argumentsSummary, Instant requestedAt) {
    }

    private static final class RunScope {
        final Set<String> approved = ConcurrentHashMap.newKeySet();
        final Set<String> denied = ConcurrentHashMap.newKeySet();
        final CopyOnWriteArrayList<PendingConfirmation> pending = new CopyOnWriteArrayList<>();
    }
}
