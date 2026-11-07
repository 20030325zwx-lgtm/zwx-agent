package com.zwx.zwxagent.agent.schedule;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 定时任务持久化。认领用 FOR UPDATE SKIP LOCKED（与媒体任务调度器同款）：
 * 本地与服务器两个后端实例可能同时对同一个库跑调度，认领必须天然防重。
 */
@Repository
public class AgentScheduleRepository {

    private static final RowMapper<ScheduledTaskRecord> TASK_MAPPER = (rs, i) -> new ScheduledTaskRecord(
            rs.getString("id"), rs.getString("tenant_id"), rs.getString("user_id"),
            rs.getString("name"), rs.getString("instruction"),
            rs.getString("conversation_id"), rs.getBoolean("session_continuity"),
            rs.getString("permission_mode"), (Long) rs.getObject("model_row_id"),
            rs.getString("schedule_kind"),
            rs.getObject("run_time") == null ? null : rs.getObject("run_time", LocalTime.class),
            (Integer) rs.getObject("weekly_dow"),
            rs.getTimestamp("once_at") == null ? null : rs.getTimestamp("once_at").toInstant(),
            rs.getBoolean("enabled"),
            rs.getTimestamp("last_run_at") == null ? null : rs.getTimestamp("last_run_at").toInstant(),
            rs.getTimestamp("next_run_at") == null ? null : rs.getTimestamp("next_run_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AgentScheduleRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public ScheduledTaskRecord insert(ScheduledTaskRecord task, Instant nextRunAt) {
        jdbc.update("""
                INSERT INTO agent_scheduled_task (id, tenant_id, user_id, name, instruction,
                    conversation_id, session_continuity, permission_mode, model_row_id,
                    schedule_kind, run_time, weekly_dow, once_at, enabled, next_run_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, task.id(), task.tenantId(), task.userId(), task.name(), task.instruction(),
                task.conversationId(), task.sessionContinuity(), task.permissionMode(), task.modelRowId(),
                task.scheduleKind(), task.runTime(), task.weeklyDow(),
                task.onceAt() == null ? null : Timestamp.from(task.onceAt()), task.enabled(),
                nextRunAt == null ? null : Timestamp.from(nextRunAt));
        return task;
    }

    public Optional<ScheduledTaskRecord> find(String tenantId, String userId, String id) {
        List<ScheduledTaskRecord> rows = jdbc.query("""
                SELECT * FROM agent_scheduled_task WHERE id = ? AND tenant_id = ? AND user_id = ?
                """, TASK_MAPPER, id, tenantId, userId);
        return rows.stream().findFirst();
    }

    public List<ScheduledTaskRecord> list(String tenantId, String userId) {
        return jdbc.query("""
                SELECT * FROM agent_scheduled_task WHERE tenant_id = ? AND user_id = ?
                ORDER BY enabled DESC, next_run_at ASC NULLS LAST, created_at DESC
                """, TASK_MAPPER, tenantId, userId);
    }

    public void update(ScheduledTaskRecord task, Instant nextRunAt) {
        jdbc.update("""
                UPDATE agent_scheduled_task SET name = ?, instruction = ?, session_continuity = ?,
                    permission_mode = ?, model_row_id = ?, schedule_kind = ?, run_time = ?,
                    weekly_dow = ?, once_at = ?, enabled = ?, next_run_at = ?, updated_at = now()
                WHERE id = ? AND tenant_id = ? AND user_id = ?
                """, task.name(), task.instruction(), task.sessionContinuity(),
                task.permissionMode(), task.modelRowId(), task.scheduleKind(), task.runTime(),
                task.weeklyDow(), task.onceAt() == null ? null : Timestamp.from(task.onceAt()),
                task.enabled(), nextRunAt == null ? null : Timestamp.from(nextRunAt),
                task.id(), task.tenantId(), task.userId());
    }

    public void setEnabled(String tenantId, String userId, String id, boolean enabled, Instant nextRunAt) {
        jdbc.update("""
                UPDATE agent_scheduled_task SET enabled = ?, next_run_at = ?, updated_at = now()
                WHERE id = ? AND tenant_id = ? AND user_id = ?
                """, enabled, nextRunAt == null ? null : Timestamp.from(nextRunAt), id, tenantId, userId);
    }

    public void delete(String tenantId, String userId, String id) {
        jdbc.update("DELETE FROM agent_scheduled_task WHERE id = ? AND tenant_id = ? AND user_id = ?",
                id, tenantId, userId);
    }

    public void bindConversation(String taskId, String conversationId) {
        jdbc.update("UPDATE agent_scheduled_task SET conversation_id = ?, updated_at = now() WHERE id = ?",
                conversationId, taskId);
    }

    /**
     * 在一个事务里锁定到期任务（SKIP LOCKED，双实例安全），由调用方在事务内完成
     * “顺延 next_run_at + 写 fire + 发起运行”——顺延必须与锁同事务，否则认领与派发
     * 之间会被另一实例重复认领。每行独立 try/catch 由服务层负责。
     */
    public void forEachDue(int limit, java.util.function.Consumer<ScheduledTaskRecord> handler) {
        tx.execute(status -> {
            List<String> ids = jdbc.queryForList("""
                    SELECT id FROM agent_scheduled_task
                    WHERE enabled AND next_run_at IS NOT NULL AND next_run_at <= now()
                    ORDER BY next_run_at LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, String.class, limit);
            for (String id : ids) {
                List<ScheduledTaskRecord> rows = jdbc.query(
                        "SELECT * FROM agent_scheduled_task WHERE id = ?", TASK_MAPPER, id);
                if (!rows.isEmpty()) handler.accept(rows.getFirst());
            }
            return null;
        });
    }

    /** 触发后顺延：ONCE 直接禁用（下次不再触发）。 */
    public void markFired(String taskId, boolean once, Instant nextRunAt) {
        if (once) {
            jdbc.update("""
                    UPDATE agent_scheduled_task SET enabled = FALSE, next_run_at = NULL,
                        last_run_at = now(), updated_at = now() WHERE id = ?
                    """, taskId);
        } else {
            jdbc.update("""
                    UPDATE agent_scheduled_task SET next_run_at = ?, last_run_at = now(), updated_at = now()
                    WHERE id = ?
                    """, nextRunAt == null ? null : Timestamp.from(nextRunAt), taskId);
        }
    }

    public void insertFire(String fireId, String taskId, String runId, String triggerKind, String error) {
        jdbc.update("""
                INSERT INTO agent_scheduled_fire (id, task_id, run_id, trigger_kind, error)
                VALUES (?, ?, ?, ?, ?)
                """, fireId, taskId, runId, triggerKind, error);
    }

    public void finishFire(String fireId, String runId, String error) {
        jdbc.update("""
                UPDATE agent_scheduled_fire SET run_id = COALESCE(?, run_id), error = ?, finished_at = now()
                WHERE id = ?
                """, runId, error, fireId);
    }

    public record FireRow(String id, String taskId, String runId, String triggerKind, String error,
                          Instant startedAt, Instant finishedAt,
                          String runStatus, String waitingReason, String conversationId) {
    }

    private static final RowMapper<FireRow> FIRE_MAPPER = (rs, i) -> new FireRow(
            rs.getString("id"), rs.getString("task_id"), rs.getString("run_id"),
            rs.getString("trigger_kind"), rs.getString("error"),
            rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
            rs.getString("run_status"), rs.getString("waiting_reason"),
            rs.getString("conversation_id"));

    /** 触发历史（可按任务过滤）：运行状态原样带出，展示口径由服务层用 RunPhase.derive 归一。 */
    public List<FireRow> listFires(String tenantId, String userId, String taskId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT f.id, f.task_id, f.run_id, f.trigger_kind, f.error, f.started_at, f.finished_at,
                       r.status AS run_status, r.waiting_reason, r.background_jobs,
                       c.id AS conversation_id
                FROM agent_scheduled_fire f
                JOIN agent_scheduled_task t ON t.id = f.task_id
                LEFT JOIN agent_run r ON r.id = f.run_id
                LEFT JOIN agent_conversation c ON c.id = t.conversation_id
                WHERE t.tenant_id = ? AND t.user_id = ?
                """);
        ArrayDeque<Object> args = new ArrayDeque<>(List.of(tenantId, userId));
        if (taskId != null && !taskId.isBlank()) {
            sql.append(" AND f.task_id = ? ");
            args.add(taskId);
        }
        sql.append(" ORDER BY f.started_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), FIRE_MAPPER, args.toArray());
    }
}
