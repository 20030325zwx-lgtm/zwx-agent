package com.zwx.zwxagent.agent.execution.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * agent_run_step 读写（整改 A1，方案 §8.1）：
 * 计划版本内的子任务持久化；依赖只允许同 run 同 plan_revision 的 step_key，写入前校验 DAG；
 * 状态流转走 state_version 乐观锁，失败即冲突，由调用方决定重试或报告。
 */
@Slf4j
@Service
public class AgentRunStepStore {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_WAITING_HUMAN = "WAITING_HUMAN";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_BLOCKED = "BLOCKED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentRunStepStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record StepDef(String stepKey, String title, String roleKey, UUID parentStepId,
                          Map<String, Object> inputSnapshot, List<String> dependsOn,
                          List<Map<String, Object>> acceptance) {
    }

    /**
     * 一次写入一个计划版本的全部步骤（同事务）；依赖引用或 DAG 非法时整体失败。
     */
    @Transactional
    public List<UUID> createSteps(String runId, String tenantId, String userId, int planRevision,
                                  List<StepDef> steps) {
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("计划不能为空");
        validateDag(steps);
        List<UUID> ids = new ArrayList<>(steps.size());
        for (StepDef step : steps) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            jdbc.update("""
                    INSERT INTO agent_run_step (id, tenant_id, user_id, run_id, plan_revision, step_key,
                        parent_step_id, title, role_key, input_snapshot, depends_on, acceptance, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                    """, id, tenantId, userId, runId, planRevision, step.stepKey(),
                    step.parentStepId(), step.title(), step.roleKey(),
                    writeJson(step.inputSnapshot == null ? Map.of() : step.inputSnapshot),
                    writeJson(step.dependsOn() == null ? List.of() : step.dependsOn()),
                    writeJson(step.acceptance() == null ? List.of() : step.acceptance()),
                    STATUS_PENDING);
        }
        return ids;
    }

    /**
     * CAS 状态流转：仅当当前状态与 state_version 都匹配时更新。
     *
     * @return false 表示版本冲突或状态已变化，调用方应重新读取后决定
     */
    public boolean transition(String runId, String tenantId, String userId, int planRevision,
                              String stepKey, String expectedStatus, String targetStatus) {
        int updated = jdbc.update("""
                UPDATE agent_run_step
                SET status = ?, state_version = state_version + 1, updated_at = CURRENT_TIMESTAMP,
                    started_at = CASE WHEN ? = 'RUNNING' AND started_at IS NULL THEN CURRENT_TIMESTAMP ELSE started_at END,
                    finished_at = CASE WHEN ? IN ('SUCCEEDED','FAILED','SKIPPED','BLOCKED','CANCELLED')
                        THEN CURRENT_TIMESTAMP ELSE finished_at END
                WHERE run_id = ? AND tenant_id = ? AND user_id = ? AND plan_revision = ? AND step_key = ?
                  AND status = ?
                """, targetStatus, targetStatus, targetStatus, runId, tenantId, userId, planRevision, stepKey,
                expectedStatus);
        return updated == 1;
    }

    public void recordResult(String runId, String tenantId, String userId, int planRevision,
                             String stepKey, String resultSummary, UUID checkpointId) {
        jdbc.update("""
                UPDATE agent_run_step
                SET result_summary = ?, checkpoint_id = ?, state_version = state_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE run_id = ? AND tenant_id = ? AND user_id = ? AND plan_revision = ? AND step_key = ?
                """, resultSummary, checkpointId, runId, tenantId, userId, planRevision, stepKey);
    }

    public List<StepRow> listByRun(String runId, String tenantId, String userId, Integer planRevision) {
        return jdbc.query("""
                SELECT id, plan_revision, step_key, parent_step_id, title, role_key,
                       depends_on::text, acceptance::text, status, attempt, state_version,
                       assignment_id, result_summary, checkpoint_id, started_at, finished_at
                FROM agent_run_step
                WHERE run_id = ? AND tenant_id = ? AND user_id = ? AND (?::int IS NULL OR plan_revision = ?)
                ORDER BY plan_revision, created_at, step_key
                """, (rs, index) -> new StepRow(
                rs.getObject("id", UUID.class), rs.getInt("plan_revision"), rs.getString("step_key"),
                rs.getObject("parent_step_id", UUID.class), rs.getString("title"), rs.getString("role_key"),
                rs.getString("depends_on"), rs.getString("acceptance"), rs.getString("status"),
                rs.getInt("attempt"), rs.getLong("state_version"), rs.getString("assignment_id"),
                rs.getString("result_summary"), rs.getObject("checkpoint_id", UUID.class),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant()),
                runId, tenantId, userId, planRevision, planRevision);
    }

    public record StepRow(UUID id, int planRevision, String stepKey, UUID parentStepId, String title,
                          String roleKey, String dependsOnJson, String acceptanceJson, String status,
                          int attempt, long stateVersion, String assignmentId, String resultSummary,
                          UUID checkpointId, Instant startedAt, Instant finishedAt) {
    }

    /**
     * 纯函数 DAG 校验：step_key 重复、依赖引用不存在、依赖自身、环。非法抛 IllegalArgumentException。
     */
    static void validateDag(List<StepDef> steps) {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (StepDef step : steps) {
            if (step.stepKey() == null || step.stepKey().isBlank()) {
                throw new IllegalArgumentException("步骤缺少 step_key");
            }
            if (!seen.add(step.stepKey())) {
                throw new IllegalArgumentException("步骤 step_key 重复：" + step.stepKey());
            }
        }
        for (StepDef step : steps) {
            Set<String> depends = new HashSet<>();
            for (String dependency : step.dependsOn() == null ? List.<String>of() : step.dependsOn()) {
                if (dependency.equals(step.stepKey())) {
                    throw new IllegalArgumentException("步骤依赖自身：" + step.stepKey());
                }
                if (!seen.contains(dependency)) {
                    throw new IllegalArgumentException("依赖的步骤不存在于同一计划版本：" + dependency);
                }
                if (!depends.add(dependency)) {
                    throw new IllegalArgumentException("依赖重复声明：" + dependency);
                }
            }
            edges.put(step.stepKey(), depends);
        }
        Map<String, Integer> visiting = new java.util.HashMap<>();
        for (String key : edges.keySet()) {
            visit(key, edges, visiting);
        }
    }

    private static void visit(String key, Map<String, Set<String>> edges, Map<String, Integer> visiting) {
        Integer state = visiting.get(key);
        if (state != null) {
            if (state == 1) throw new IllegalArgumentException("步骤依赖形成环：" + key);
            return;
        }
        visiting.put(key, 1);
        for (String next : edges.get(key)) visit(next, edges, visiting);
        visiting.put(key, 2);
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception cause) {
            throw new IllegalArgumentException("步骤 JSON 序列化失败", cause);
        }
    }
}
