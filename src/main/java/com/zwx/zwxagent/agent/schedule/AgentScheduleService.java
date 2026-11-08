package com.zwx.zwxagent.agent.schedule;

import com.zwx.zwxagent.agent.execution.AgentRunStateService;
import com.zwx.zwxagent.agent.execution.RunPhase;
import com.zwx.zwxagent.chat.conversation.AgentConversationService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.capability.tools.PermissionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 定时任务（对标 ZCode automations）：到点以自然语言指令在任务专属会话里拉起一次智能体运行。
 * 执行完全复用智能体底座——权限五档、工具确认挂起、配额、轨迹；这里只做时间触发与运行聚合。
 */
@Service
public class AgentScheduleService {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduleService.class);
    private static final String SUPER_AGENT_KEY = "super";
    private static final int MAX_TASKS_PER_USER = 20;

    private final AgentScheduleRepository repo;
    private final AgentConversationService conversations;
    private final AgentRunStateService runState;
    private final com.zwx.zwxagent.agent.execution.RunQuotaService runQuota;
    private final boolean enabled;

    public AgentScheduleService(AgentScheduleRepository repo, AgentConversationService conversations,
                                AgentRunStateService runState,
                                com.zwx.zwxagent.agent.execution.RunQuotaService runQuota,
                                @Value("${app.schedules.enabled:true}") String enabledFlag) {
        this.repo = repo;
        this.conversations = conversations;
        this.runState = runState;
        this.runQuota = runQuota;
        this.enabled = !"false".equalsIgnoreCase(enabledFlag == null ? "" : enabledFlag.trim());
    }

    public boolean schedulerEnabled() {
        return enabled;
    }

    // ── 查询 ────────────────────────────────────────────────────────────────

    public record FireView(String id, String taskId, String taskName, String runId, String triggerKind,
                           String phase, String error, Instant startedAt, Instant finishedAt,
                           String conversationId) {
    }

    public record TaskView(ScheduledTaskRecord task, String scheduleText, FireView lastFire) {
    }

    public List<TaskView> list(CurrentActor actor) {
        List<ScheduledTaskRecord> tasks = repo.list(actor.tenantId(), actor.userId());
        Map<String, FireView> lastByTask = new LinkedHashMap<>();
        for (AgentScheduleRepository.FireRow row : repo.listFires(actor.tenantId(), actor.userId(), null, 200)) {
            lastByTask.putIfAbsent(row.taskId(), toFireView(row, tasks));
        }
        List<TaskView> views = new ArrayList<>();
        for (ScheduledTaskRecord task : tasks) {
            views.add(new TaskView(task, ScheduleTimes.describe(task.schedule()), lastByTask.get(task.id())));
        }
        return views;
    }

    public List<FireView> fires(CurrentActor actor, String taskId, int limit) {
        return repo.listFires(actor.tenantId(), actor.userId(), taskId, Math.max(1, Math.min(limit, 100)))
                .stream().map(row -> toFireView(row, repo.find(actor.tenantId(), actor.userId(), row.taskId())
                        .map(List::of).orElse(List.of()))).toList();
    }

    private FireView toFireView(AgentScheduleRepository.FireRow row, List<ScheduledTaskRecord> tasks) {
        String taskName = tasks.stream().filter(t -> t.id().equals(row.taskId())).findFirst()
                .map(ScheduledTaskRecord::name).orElse("");
        return new FireView(row.id(), row.taskId(), taskName, row.runId(), row.triggerKind(),
                RunPhase.derive(row.runStatus(), row.waitingReason(), 0).name(),
                row.error(), row.startedAt(), row.finishedAt(), row.conversationId());
    }

    // ── 写操作 ──────────────────────────────────────────────────────────────

    public record SaveBody(String name, String instruction, String permissionMode, Long modelRowId,
                           String scheduleKind, String runTime, Integer weeklyDow, Instant onceAt,
                           Boolean sessionContinuity) {
    }

    public ScheduledTaskRecord create(CurrentActor actor, SaveBody body) {
        List<ScheduledTaskRecord> existing = repo.list(actor.tenantId(), actor.userId());
        if (existing.size() >= MAX_TASKS_PER_USER) {
            throw new IllegalArgumentException("每个用户最多 " + MAX_TASKS_PER_USER + " 个定时任务");
        }
        ScheduledTaskRecord task = build(actor, UUID.randomUUID().toString(), body);
        Instant next = ScheduleTimes.initialNextRun(task.schedule(), Instant.now());
        repo.insert(task, next);
        // 回填库端值（next_run_at 等由计算得出，内存对象不带）
        return repo.find(actor.tenantId(), actor.userId(), task.id()).orElse(task);
    }

    public ScheduledTaskRecord update(CurrentActor actor, String id, SaveBody body) {
        ScheduledTaskRecord current = repo.find(actor.tenantId(), actor.userId(), id)
                .orElseThrow(() -> new IllegalArgumentException("定时任务不存在"));
        ScheduledTaskRecord merged = build(actor, id, body);
        // 会话归属不被编辑重置；保留既有 conversation_id
        merged = new ScheduledTaskRecord(merged.id(), merged.tenantId(), merged.userId(), merged.name(),
                merged.instruction(), current.conversationId(), merged.sessionContinuity(),
                merged.permissionMode(), merged.modelRowId(), merged.scheduleKind(), merged.runTime(),
                merged.weeklyDow(), merged.onceAt(), merged.enabled(), current.lastRunAt(),
                current.nextRunAt(), current.createdAt(), current.updatedAt());
        Instant next = merged.enabled() ? ScheduleTimes.initialNextRun(merged.schedule(), Instant.now()) : null;
        repo.update(merged, next);
        return merged;
    }

    public void setEnabled(CurrentActor actor, String id, boolean enabled) {
        ScheduledTaskRecord task = repo.find(actor.tenantId(), actor.userId(), id)
                .orElseThrow(() -> new IllegalArgumentException("定时任务不存在"));
        Instant next = enabled ? ScheduleTimes.initialNextRun(task.schedule(), Instant.now()) : null;
        repo.setEnabled(actor.tenantId(), actor.userId(), id, enabled, next);
    }

    public void delete(CurrentActor actor, String id) {
        repo.delete(actor.tenantId(), actor.userId(), id);
    }

    private ScheduledTaskRecord build(CurrentActor actor, String id, SaveBody body) {
        if (body == null || body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("任务名称必填");
        }
        if (body.instruction() == null || body.instruction().isBlank()) {
            throw new IllegalArgumentException("任务指令必填");
        }
        if (body.name().length() > 64) throw new IllegalArgumentException("任务名称过长（≤64 字）");
        if (body.instruction().length() > 4000) throw new IllegalArgumentException("任务指令过长（≤4000 字）");
        String kind = body.scheduleKind() == null ? "DAILY" : body.scheduleKind().trim().toUpperCase();
        LocalTime runTime = body.runTime() == null || body.runTime().isBlank()
                ? null : LocalTime.parse(body.runTime().trim());
        Integer dow = kind.equals("WEEKLY") ? body.weeklyDow() : null;
        if (dow != null && !ScheduleTimes.isValidIsoDow(dow)) {
            throw new IllegalArgumentException("星期几无效（1=周一 … 7=周日）");
        }
        Instant onceAt = kind.equals("ONCE") ? body.onceAt() : null;
        return new ScheduledTaskRecord(id, actor.tenantId(), actor.userId(), body.name().trim(),
                body.instruction().trim(), null,
                body.sessionContinuity() == null || body.sessionContinuity(),
                PermissionMode.parse(body.permissionMode()).name(), body.modelRowId(),
                kind, runTime, dow, onceAt, true, null, null, null, null);
    }

    // ── 触发 ────────────────────────────────────────────────────────────────

    /** 手动立即触发（也是联调的主要入口）。 */
    public FireView fireNow(CurrentActor actor, String taskId) {
        ScheduledTaskRecord task = repo.find(actor.tenantId(), actor.userId(), taskId)
                .orElseThrow(() -> new IllegalArgumentException("定时任务不存在"));
        String fireId = dispatch(task, "MANUAL");
        if (!task.once()) {
            Instant next = ScheduleTimes.nextRun(task.schedule(), Instant.now());
            repo.markFired(task.id(), false, next);
        }
        return repo.listFires(actor.tenantId(), actor.userId(), taskId, 50).stream()
                .filter(row -> row.id().equals(fireId)).findFirst()
                .map(row -> toFireView(row, List.of(task)))
                .orElseThrow(() -> new IllegalStateException("触发记录缺失"));
    }

    /** 调度器入口：认领到期任务并逐个触发（在认领事务内完成，双实例安全）。 */
    public void fireDue() {
        if (!enabled) return;
        repo.forEachDue(5, task -> {
            dispatch(task, "SCHEDULE");
            repo.markFired(task.id(), task.once(), ScheduleTimes.nextRun(task.schedule(), Instant.now()));
        });
    }

    /**
     * 发起一次运行：会话 → 用户轮次 → StartCommand（executionMode=SCHEDULED）→ 助手占位。
     * 失败不抛出——记入 fire 的 error，任务顺延照常（调度不能被单次失败卡死）。
     */
    private String dispatch(ScheduledTaskRecord task, String triggerKind) {
        String fireId = UUID.randomUUID().toString();
        String tenantId = task.tenantId();
        String userId = task.userId();
        try {
            runQuota.checkRunAllowed(userId);
            boolean continuity = task.sessionContinuity();
            String conversationId = continuity && task.conversationId() != null
                    ? task.conversationId() : UUID.randomUUID().toString();
            String instruction = """
                    %s

                    [定时任务 · 无人值守运行]
                    当前没有用户在线：请自主完成以上任务，不要向用户提问（不确定处按最合理方式继续，并在结论中说明）；
                    结束时用简短结论汇总：做了什么、产出了什么、在哪里查看。""".formatted(task.instruction());
            conversations.ensureConversation(tenantId, userId, SUPER_AGENT_KEY, conversationId,
                    task.name(), instruction);
            String clientRequestId = "sched-" + fireId;
            long userMessageId = conversations.startUserTurn(tenantId, userId, SUPER_AGENT_KEY,
                    conversationId, instruction, clientRequestId);
            var command = new AgentRunStateService.StartCommand(conversationId, tenantId, userId,
                    userMessageId, instruction, "", "", task.permissionMode(),
                    "SCHEDULED", clientRequestId);
            var startResult = runState.start(command, null);
            conversations.appendAssistantReply(tenantId, userId, SUPER_AGENT_KEY, conversationId,
                    userMessageId, "", "IN_PROGRESS", startResult.runId(), null);
            if (continuity && task.conversationId() == null) {
                repo.bindConversation(task.id(), conversationId);
            }
            repo.insertFire(fireId, task.id(), startResult.runId(), triggerKind, null);
            log.info("定时任务触发：task={} fire={} run={} trigger={}", task.id(), fireId,
                    startResult.runId(), triggerKind);
        } catch (Exception exception) {
            String error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            repo.insertFire(fireId, task.id(), null, triggerKind, error);
            log.warn("定时任务触发失败：task={} fire={} 原因={}", task.id(), fireId, error);
        }
        return fireId;
    }
}
