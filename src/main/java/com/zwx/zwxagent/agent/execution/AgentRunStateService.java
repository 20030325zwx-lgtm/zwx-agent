package com.zwx.zwxagent.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 工作智能体整改 A2：engine_version=2 的统一运行状态服务（方案 §4.3/§4.4/§9）。
 * <ul>
 *   <li>启动：start_request_key 幂等，同事务写 run（QUEUED）与 run.created 事件；</li>
 *   <li>派发：单语句 FOR UPDATE SKIP LOCKED 认领并递增 fencing_token，事务外执行；</li>
 *   <li>控制：pause/cancel 只写 control_state，执行器在安全点应用（phase→STOPPED）；</li>
 *   <li>答复：agent_run_input 消费一次后原 run 重新入队，不新建 run；</li>
 *   <li>终态：CAS 在 owner_instance+fencing_token 匹配时写入，拒绝旧执行器迟到回写。</li>
 * </ul>
 * 旧 status 由本服务投影：QUEUED/WORKING→RUNNING；WAITING_HUMAN/BLOCKED/STOPPED→INTERRUPTED；DONE→COMPLETED。
 */
@Slf4j
@Service
public class AgentRunStateService {

    public static final String PHASE_QUEUED = "QUEUED";
    public static final String PHASE_WORKING = "WORKING";
    public static final String PHASE_WAITING_HUMAN = "WAITING_HUMAN";
    public static final String PHASE_BLOCKED = "BLOCKED";
    public static final String PHASE_STOPPED = "STOPPED";
    public static final String PHASE_DONE = "DONE";
    public static final String PHASE_FAILED = "FAILED";

    public static final String CONTROL_NONE = "NONE";
    public static final String CONTROL_PAUSE_REQUESTED = "PAUSE_REQUESTED";
    public static final String CONTROL_CANCEL_REQUESTED = "CANCEL_REQUESTED";

    public static final String STOP_USER_PAUSED = "USER_PAUSED";
    public static final String STOP_USER_CANCELLED = "USER_CANCELLED";
    public static final String STOP_UNKNOWN_EFFECT = "UNKNOWN_EFFECT";
    public static final String STOP_LEASE_EXHAUSTED = "LEASE_RETRY_EXHAUSTED";

    public static final int ENGINE_VERSION = 2;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final long leaseSeconds;
    private final int maxAutoResumeAttempts;

    public AgentRunStateService(JdbcTemplate jdbc,
                                @Value("${app.agent.dispatch.lease-seconds:90}") long leaseSeconds,
                                @Value("${app.agent.dispatch.max-auto-resume-attempts:3}") int maxAutoResumeAttempts) {
        this.jdbc = jdbc;
        this.leaseSeconds = leaseSeconds;
        this.maxAutoResumeAttempts = maxAutoResumeAttempts;
    }

    public record StartCommand(String conversationId, String tenantId, String userId, long userMessageId,
                               String input, String historyContext, String knowledgeContext,
                               String permissionMode, String executionMode, String clientRequestId) {
    }

    public record StartResult(String runId, String assistantMessageId, boolean duplicated) {
    }

    /**
     * 启动 engine_version=2 的 run：start_request_key 幂等（重复请求返回既有 run）；
     * 同事务写 run（QUEUED，等待派发）与 run.created 事件。assistantMessageId 由调用方先行创建占位消息后传入。
     */
    @Transactional
    public StartResult start(StartCommand command, Long assistantMessageId) {
        if (command.clientRequestId() == null || command.clientRequestId().isBlank()) {
            throw new IllegalArgumentException("clientRequestId 必填（启动幂等）");
        }
        UUID runId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO agent_run (id, conversation_id, tenant_id, user_id, agent_key, user_message_id,
                    assistant_message_id, status, phase, input, history_context, knowledge_context,
                    permission_mode, engine_version, execution_mode, trigger_source, start_request_key,
                    next_dispatch_at, recovery_policy, owner_instance, lease_until, last_heartbeat_at)
                SELECT ?, c.id, ?, ?, 'super', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CHAT', ?, NOW(),
                    'MANUAL', NULL, NULL, NULL
                FROM agent_conversation c
                WHERE c.id = ? AND c.tenant_id = ? AND c.user_id = ?
                ON CONFLICT (tenant_id, user_id, start_request_key) WHERE start_request_key IS NOT NULL DO NOTHING
                """, runId.toString(), command.tenantId(), command.userId(), command.userMessageId(),
                assistantMessageId, "RUNNING", PHASE_QUEUED, command.input(), command.historyContext(),
                command.knowledgeContext(), command.permissionMode(), ENGINE_VERSION, command.executionMode(),
                command.clientRequestId(), command.conversationId(), command.tenantId(), command.userId());
        if (inserted == 0) {
            String existing = jdbc.queryForObject("""
                    SELECT id FROM agent_run WHERE tenant_id = ? AND user_id = ? AND start_request_key = ?
                    """, String.class, command.tenantId(), command.userId(), command.clientRequestId());
            log.info("启动请求幂等命中既有 run；requestKey={}, runId={}", command.clientRequestId(), existing);
            return new StartResult(existing, assistantMessageId == null ? null : assistantMessageId.toString(), true);
        }
        recordEvent(runId.toString(), command.tenantId(), command.userId(), "run",
                "run.created", Map.of("input", command.input()), "run:" + command.clientRequestId());
        return new StartResult(runId.toString(), assistantMessageId == null ? null : assistantMessageId.toString(), false);
    }

    /** 启动幂等查询：同 (tenant, user, start_request_key) 的既有 run。 */
    public Optional<String> findByStartRequestKey(String tenantId, String userId, String clientRequestId) {
        List<String> rows = jdbc.query(
                "SELECT id FROM agent_run WHERE tenant_id = ? AND user_id = ? AND start_request_key = ?",
                (rs, index) -> rs.getString(1), tenantId, userId, clientRequestId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public record ClaimedRun(String id, String tenantId, String userId, String conversationId,
                             long userMessageId, long fencingToken, int resumeCount,
                             String input, String historyContext, String knowledgeContext,
                             String permissionMode, String lastNode, String nextNode, String planJson) {
    }

    /**
     * 认领一个待派发的 engine_version=2 run：SKIP LOCKED 防多执行器争抢，fencing_token 递增防迟到写。
     */
    public Optional<ClaimedRun> claim(String ownerInstance) {
        List<ClaimedRun> rows = jdbc.query("""
                UPDATE agent_run
                SET phase = 'WORKING', owner_instance = ?, lease_until = NOW() + (? || ' seconds')::interval,
                    last_heartbeat_at = NOW(), fencing_token = fencing_token + 1,
                    state_version = state_version + 1, next_dispatch_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = (SELECT id FROM agent_run
                            WHERE phase = 'QUEUED' AND engine_version = 2 AND control_state = 'NONE'
                              AND (next_dispatch_at IS NULL OR next_dispatch_at <= NOW())
                            ORDER BY COALESCE(next_dispatch_at, created_at), id
                            FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id, tenant_id, user_id, conversation_id, user_message_id, fencing_token,
                          resume_count, input, history_context, knowledge_context, permission_mode,
                          last_node, next_node, plan::text
                """, (rs, index) -> new ClaimedRun(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("user_id"), rs.getString("conversation_id"), rs.getLong("user_message_id"),
                rs.getLong("fencing_token"), rs.getInt("resume_count"), rs.getString("input"),
                rs.getString("history_context"), rs.getString("knowledge_context"), rs.getString("permission_mode"),
                rs.getString("last_node"), rs.getString("next_node"), rs.getString("plan")),
                ownerInstance, String.valueOf(leaseSeconds));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** 延长本实例持有的 engine_version=2 工作中 run 的租约（批量心跳）。 */
    public int heartbeat(String ownerInstance) {
        return jdbc.update("""
                UPDATE agent_run
                SET lease_until = NOW() + (? || ' seconds')::interval, last_heartbeat_at = NOW()
                WHERE phase = 'WORKING' AND engine_version = ? AND owner_instance = ?
                """, String.valueOf(leaseSeconds), ENGINE_VERSION, ownerInstance);
    }

    /** 回收租约过期的 run：有界自动重试（有检查点→重新排队），超限转 BLOCKED。 */
    public int reapExpiredLeases() {
        int finalized = jdbc.update("""
                UPDATE agent_run
                SET phase = 'STOPPED', status = 'INTERRUPTED',
                    stop_reason = CASE control_state WHEN 'CANCEL_REQUESTED' THEN 'USER_CANCELLED' ELSE 'USER_PAUSED' END,
                    control_state = 'NONE', state_version = state_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE phase = 'QUEUED' AND engine_version = ? AND control_state <> 'NONE'
                """
                , ENGINE_VERSION);
        int requeued = jdbc.update("""
                UPDATE agent_run
                SET phase = 'QUEUED', next_dispatch_at = NOW() + INTERVAL '30 seconds',
                    owner_instance = NULL, lease_until = NULL, state_version = state_version + 1,
                    resume_count = resume_count + 1, updated_at = CURRENT_TIMESTAMP
                WHERE phase = 'WORKING' AND engine_version = ? AND lease_until < NOW()
                  AND resume_count < ?
                """, ENGINE_VERSION, maxAutoResumeAttempts);
        int blocked = jdbc.update("""
                UPDATE agent_run
                SET phase = 'BLOCKED', status = 'INTERRUPTED', stop_reason = ?, owner_instance = NULL,
                    lease_until = NULL, state_version = state_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE phase = 'WORKING' AND engine_version = ? AND lease_until < NOW()
                """, STOP_LEASE_EXHAUSTED, ENGINE_VERSION);
        if (requeued > 0 || blocked > 0 || finalized > 0) {
            log.warn("派发扫尾：排队终态 {} 个，租约重排 {} 个，转阻塞 {} 个", finalized, requeued, blocked);
        }
        return finalized + requeued + blocked;
    }

    /**
     * 会话执行槽被占用时，把一次认领原样退回队列（延迟再派发）；不消耗 resume_count。
     * 仅当 run 仍处于本实例的 WORKING 租约下才回退（CAS 带 owner+fencing）。
     */
    public boolean requeueClaim(String runId, String ownerInstance, long fencingToken, int delaySeconds) {
        int updated = jdbc.update("""
                UPDATE agent_run
                SET phase = 'QUEUED', owner_instance = NULL, lease_until = NULL, last_heartbeat_at = NULL,
                    next_dispatch_at = NOW() + (? || ' seconds')::interval,
                    state_version = state_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_instance = ? AND fencing_token = ? AND phase = 'WORKING' AND engine_version = ?
                """, String.valueOf(delaySeconds), runId, ownerInstance, fencingToken, ENGINE_VERSION);
        return updated == 1;
    }

    /** 用户暂停/取消请求：只写 control_state（CAS 校验 state_version），由执行器在安全点应用。 */
    public boolean requestControl(String runId, String tenantId, String userId,
                                  String controlState, Long expectedVersion) {
        if (!CONTROL_PAUSE_REQUESTED.equals(controlState) && !CONTROL_CANCEL_REQUESTED.equals(controlState)) {
            throw new IllegalArgumentException("非法的控制请求：" + controlState);
        }
        int updated;
        if (expectedVersion == null) {
            updated = jdbc.update("""
                    UPDATE agent_run SET control_state = ?, state_version = state_version + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND tenant_id = ? AND user_id = ? AND engine_version = ?
                      AND phase IN ('QUEUED', 'WORKING') AND control_state = ?
                    """, controlState, runId, tenantId, userId, ENGINE_VERSION, CONTROL_NONE);
        } else {
            updated = jdbc.update("""
                    UPDATE agent_run SET control_state = ?, state_version = state_version + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND tenant_id = ? AND user_id = ? AND engine_version = ?
                      AND phase IN ('QUEUED', 'WORKING') AND control_state = ? AND state_version = ?
                    """, controlState, runId, tenantId, userId, ENGINE_VERSION, CONTROL_NONE, expectedVersion);
        }
        return updated == 1;
    }

    /**
     * 执行器安全点检查：控制请求就绪时落 STOPPED（携带 fencing，拒绝旧执行器）并返回 true 要求停止。
     * QUEUED 阶段的取消/暂停直接在派发前生效（认领 SQL 不取 control_state<>NONE 的 run）。
     */
    public boolean shouldStopAtSafePoint(String runId, String ownerInstance, long fencingToken) {
        String control = jdbc.query("""
                SELECT control_state FROM agent_run
                WHERE id = ? AND owner_instance = ? AND fencing_token = ? AND phase = 'WORKING' AND engine_version = ?
                """, rs -> rs.next() ? rs.getString(1) : null, runId, ownerInstance, fencingToken, ENGINE_VERSION);
        if (CONTROL_PAUSE_REQUESTED.equals(control)) {
            return applyStop(runId, ownerInstance, fencingToken, CONTROL_PAUSE_REQUESTED, STOP_USER_PAUSED);
        }
        if (CONTROL_CANCEL_REQUESTED.equals(control)) {
            return applyStop(runId, ownerInstance, fencingToken, CONTROL_CANCEL_REQUESTED, STOP_USER_CANCELLED);
        }
        return false;
    }

    private boolean applyStop(String runId, String ownerInstance, long fencingToken,
                              String expectedControl, String stopReason) {
        int updated = jdbc.update("""
                UPDATE agent_run
                SET phase = 'STOPPED', status = 'INTERRUPTED', stop_reason = ?, control_state = ?,
                    state_version = state_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_instance = ? AND fencing_token = ? AND control_state = ? AND phase = 'WORKING'
                """, stopReason, CONTROL_NONE, runId, ownerInstance, fencingToken, expectedControl);
        return updated == 1;
    }

    /**
     * 人工答复后原 run 重新入队（同任务续答，不新建 run）：答复文本并入 history_context，
     * 最初用户请求（input 列）不重写。WAITING_HUMAN → QUEUED。
     */
    @Transactional
    public boolean requeueAfterAnswer(String runId, String tenantId, String userId, String answerText) {
        String suffix = answerText == null || answerText.isBlank() ? "" :
                "\n\n【用户补充答复】\n" + answerText;
        int updated = jdbc.update("""
                UPDATE agent_run
                SET phase = 'QUEUED', status = 'RUNNING', waiting_reason = NULL, waiting_detail = NULL,
                    next_dispatch_at = NOW(), state_version = state_version + 1, updated_at = CURRENT_TIMESTAMP,
                    history_context = COALESCE(history_context, '') || ?
                WHERE id = ? AND tenant_id = ? AND user_id = ? AND engine_version = ? AND phase = ?
                """, suffix, runId, tenantId, userId, ENGINE_VERSION, PHASE_WAITING_HUMAN);
        return updated == 1;
    }

    /** 恢复一个已停止（用户暂停）的 engine_version=2 run：清除 stop_reason 并重新排队。 */
    public boolean resume(String runId, String tenantId, String userId) {
        int updated = jdbc.update("""
                UPDATE agent_run
                SET phase = 'QUEUED', status = 'RUNNING', control_state = ?, stop_reason = NULL,
                    next_dispatch_at = NOW(), state_version = state_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND user_id = ? AND engine_version = ? AND phase = ?
                """, CONTROL_NONE, runId, tenantId, userId, ENGINE_VERSION, PHASE_STOPPED);
        return updated == 1;
    }

    /**
     * 执行器终态回写：CAS（owner+fencing+WORKING）防迟到写。等待人工的 run（AskHuman）不在此收口。
     */
    public boolean complete(String runId, String ownerInstance, long fencingToken,
                            String projectedStatus, String answer, String error) {
        String phase = "COMPLETED".equals(projectedStatus) ? PHASE_DONE : PHASE_FAILED;
        int updated = jdbc.update("""
                UPDATE agent_run
                SET status = ?, phase = ?, stop_reason = CASE WHEN ? = 'FAILED' THEN stop_reason ELSE NULL END,
                    final_answer = COALESCE(?, final_answer), partial_answer = COALESCE(?, partial_answer),
                    error_message = ?, state_version = state_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND owner_instance = ? AND fencing_token = ? AND phase = 'WORKING' AND engine_version = ?
                """, projectedStatus, phase, projectedStatus, answer, answer, error,
                runId, ownerInstance, fencingToken, ENGINE_VERSION);
        return updated == 1;
    }

    /** 当前事件游标（快照用）。 */
    public long eventCursor(String runId) {
        Long seq = jdbc.queryForObject(
                "SELECT event_seq FROM agent_run WHERE id = ?", Long.class, runId);
        return seq == null ? 0 : seq;
    }

    /** 版本读取（快照用）；run 不存在返回空。 */
    public Optional<Long> stateVersion(String runId, String tenantId, String userId) {
        List<Long> rows = jdbc.query(
                "SELECT state_version FROM agent_run WHERE id = ? AND tenant_id = ? AND user_id = ?",
                (rs, index) -> rs.getLong(1), runId, tenantId, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** 记录结构化事件（run 存在时走 event_seq 分配器，见 AgentExecutionTraceService）。 */
    void recordEvent(String runId, String tenantId, String userId, String eventType,
                     String summary, Map<String, Object> detail, String dedupeKey) {
        Long seq;
        try {
            seq = jdbc.queryForObject(
                    "UPDATE agent_run SET event_seq = event_seq + 1 WHERE id = ? RETURNING event_seq",
                    Long.class, runId);
        } catch (Exception cause) {
            throw new IllegalStateException("事件序号分配失败（run 不存在）", cause);
        }
        jdbc.update("""
                INSERT INTO agent_execution_event (run_id, tenant_id, agent_key, conversation_id, sequence,
                    phase, summary, detail, user_id, event_type, schema_version, dedupe_key)
                SELECT ?, ?, 'super', c.id, ?, ?, ?, ?::jsonb, ?, ?, 1, ?
                FROM agent_conversation c WHERE c.id = (
                    SELECT conversation_id FROM agent_run WHERE id = ?)
                ON CONFLICT (run_id, dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING
                """, runId, tenantId, seq, "run", summary, writeJson(detail), userId, eventType, dedupeKey, runId);
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception cause) {
            throw new IllegalArgumentException("事件 detail 序列化失败", cause);
        }
    }

    /** 快照视图里的时间透传用（避免服务层泄漏 JDBC 类型）。 */
    public record RunSnapshot(String runId, String phase, String status, String controlState, String stopReason,
                              String waitingReason, String waitingDetail, String projectedStatus,
                              long eventCursor, long stateVersion, String ownerInstance, Instant leaseUntil) {
    }
}
