package com.zwx.zwxagent.capability.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 权限判定服务：把「用户规则 + 权限模式 + 工具风险」合成为三值判定 ALLOW / ASK / DENY。
 *
 * <p>判定顺序：DENY 规则 > ALLOW 规则 > 模式矩阵。规则按 run 发起时快照加载，
 * 同一 run 内判定稳定；未注册的 run（单智能体等旧路径）不受模式约束，保持原有行为。</p>
 */
@Slf4j
@Service
public class PermissionService {

    public enum Decision {
        ALLOW, ASK, DENY
    }

    /** 工具权限规则；尾部 * 表示前缀通配（如 mcp:*）。 */
    public record Rule(String toolPattern, boolean allow) {

        public boolean matches(String toolName) {
            if (toolName == null || toolName.isBlank()) return false;
            if (toolPattern.equals(toolName)) return true;
            if (toolPattern.endsWith("*")) {
                return toolName.startsWith(toolPattern.substring(0, toolPattern.length() - 1));
            }
            return false;
        }
    }

    public record RuleView(long id, String toolPattern, String effect) {
    }

    private record RunScope(PermissionMode mode, List<Rule> rules) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final Set<String> alwaysAskTools;
    private final ConcurrentHashMap<String, RunScope> runs = new ConcurrentHashMap<>();

    @Autowired
    public PermissionService(
            JdbcTemplate jdbcTemplate,
            @Value("${app.tools.confirmation.required-tools:applyProjectPatch,runProjectTask,commitProjectChanges,executeTerminalCommand,executeDatabaseQuery,queryExternalDatabase,downloadResource,mcp:*}")
            String alwaysAskTools) {
        this.jdbcTemplate = jdbcTemplate;
        this.alwaysAskTools = alwaysAskTools == null || alwaysAskTools.isBlank()
                ? Set.of()
                : Arrays.stream(alwaysAskTools.split(","))
                        .map(String::trim).filter(value -> !value.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
    }

    // ---------------------------------------------------------------------
    // 用户设置
    // ---------------------------------------------------------------------

    /** 用户默认模式；无记录或读取失败时回落到 EDIT（与引入模式前行为一致）。 */
    public PermissionMode defaultMode(String tenantId, String userId) {
        if (tenantId == null || userId == null) return PermissionMode.DEFAULT;
        try {
            List<String> modes = jdbcTemplate.queryForList(
                    "SELECT permission_mode FROM agent_user_setting WHERE tenant_id = ? AND user_id = ?",
                    String.class, tenantId, userId);
            return modes.isEmpty() ? PermissionMode.DEFAULT : PermissionMode.parse(modes.get(0));
        } catch (Exception exception) {
            log.warn("读取用户权限模式失败，使用默认值；tenantId={}, userId={}", tenantId, userId, exception);
            return PermissionMode.DEFAULT;
        }
    }

    public PermissionMode saveDefaultMode(String tenantId, String userId, PermissionMode mode) {
        jdbcTemplate.update("""
                INSERT INTO agent_user_setting (tenant_id, user_id, permission_mode)
                VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, user_id)
                DO UPDATE SET permission_mode = EXCLUDED.permission_mode, updated_at = CURRENT_TIMESTAMP
                """, tenantId, userId, mode.name());
        return mode;
    }

    public List<RuleView> rules(String tenantId, String userId) {
        return jdbcTemplate.query("""
                SELECT id, tool_pattern, effect FROM agent_permission_rule
                WHERE tenant_id = ? AND user_id = ? ORDER BY id
                """, (rs, rowNum) -> new RuleView(
                        rs.getLong("id"), rs.getString("tool_pattern"), rs.getString("effect")),
                tenantId, userId);
    }

    public RuleView addRule(String tenantId, String userId, String toolPattern, String effect) {
        String normalizedPattern = toolPattern == null ? "" : toolPattern.trim();
        if (normalizedPattern.isEmpty()) throw new IllegalArgumentException("工具匹配模式不能为空");
        String normalizedEffect = effect == null ? "" : effect.trim().toUpperCase();
        if (!"ALLOW".equals(normalizedEffect) && !"DENY".equals(normalizedEffect)) {
            throw new IllegalArgumentException("无效的规则效果：" + effect);
        }
        jdbcTemplate.update("""
                INSERT INTO agent_permission_rule (tenant_id, user_id, tool_pattern, effect)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id, tool_pattern, effect) DO NOTHING
                """, tenantId, userId, normalizedPattern, normalizedEffect);
        return jdbcTemplate.queryForObject("""
                SELECT id, tool_pattern, effect FROM agent_permission_rule
                WHERE tenant_id = ? AND user_id = ? AND tool_pattern = ? AND effect = ?
                """, (rs, rowNum) -> new RuleView(
                        rs.getLong("id"), rs.getString("tool_pattern"), rs.getString("effect")),
                tenantId, userId, normalizedPattern, normalizedEffect);
    }

    public boolean deleteRule(String tenantId, String userId, long ruleId) {
        return jdbcTemplate.update("""
                DELETE FROM agent_permission_rule WHERE id = ? AND tenant_id = ? AND user_id = ?
                """, ruleId, tenantId, userId) > 0;
    }

    // ---------------------------------------------------------------------
    // run 级判定
    // ---------------------------------------------------------------------

    /** 图编排器在 run 开始时注册：模式与规则在此刻快照。 */
    public void registerRun(String runId, PermissionMode mode, String tenantId, String userId) {
        if (runId == null || runId.isBlank()) return;
        runs.put(runId, new RunScope(mode == null ? PermissionMode.DEFAULT : mode, loadRules(tenantId, userId)));
    }

    public void releaseRun(String runId) {
        if (runId != null) runs.remove(runId);
    }

    public boolean isRegistered(String runId) {
        return runId != null && runs.containsKey(runId);
    }

    /** 未注册 run 返回 ALLOW，调用方（ToolGuardHook）只在 run 已注册时启用模式判定。 */
    public Decision decide(String runId, String toolName) {
        RunScope scope = runs.get(runId);
        if (scope == null) return Decision.ALLOW;
        return decide(scope.mode(), scope.rules(), toolName);
    }

    /** 纯判定：便于单测覆盖完整矩阵。 */
    public Decision decide(PermissionMode mode, List<Rule> rules, String toolName) {
        if (rules != null) {
            for (Rule rule : rules) {
                if (!rule.allow() && rule.matches(toolName)) return Decision.DENY;
            }
            for (Rule rule : rules) {
                if (rule.allow() && rule.matches(toolName)) return Decision.ALLOW;
            }
        }
        ToolRisk risk = riskOf(toolName);
        return switch (mode == null ? PermissionMode.DEFAULT : mode) {
            case PLAN -> risk == ToolRisk.READ_ONLY ? Decision.ALLOW : Decision.DENY;
            case BUILD -> risk == ToolRisk.READ_ONLY ? Decision.ALLOW : Decision.ASK;
            case EDIT -> risk == ToolRisk.READ_ONLY || risk == ToolRisk.WRITE
                    ? Decision.ALLOW : Decision.ASK;
            case GUARDED -> risk == ToolRisk.DESTRUCTIVE ? Decision.ASK : Decision.ALLOW;
            case YOLO -> Decision.ALLOW;
        };
    }

    /** 风险等级：登记表为准；历史配置项 required-tools 作为最低 EXECUTE 的抬升项，保持既有拦截面。 */
    public ToolRisk riskOf(String toolName) {
        ToolRisk risk = ToolRiskCatalog.riskOf(toolName);
        boolean forced = alwaysAskTools.contains(toolName)
                || (toolName != null && toolName.startsWith("mcp:") && alwaysAskTools.contains("mcp:*"));
        if (forced && risk.ordinal() < ToolRisk.EXECUTE.ordinal()) return ToolRisk.EXECUTE;
        return risk;
    }

    private List<Rule> loadRules(String tenantId, String userId) {
        if (tenantId == null || userId == null) return List.of();
        try {
            return jdbcTemplate.query("""
                    SELECT tool_pattern, effect FROM agent_permission_rule
                    WHERE tenant_id = ? AND user_id = ? ORDER BY id
                    """, (rs, rowNum) -> new Rule(
                            rs.getString("tool_pattern"), "ALLOW".equals(rs.getString("effect"))),
                    tenantId, userId);
        } catch (Exception exception) {
            log.warn("加载权限规则失败，本次 run 仅按模式判定；tenantId={}, userId={}", tenantId, userId, exception);
            return List.of();
        }
    }
}
