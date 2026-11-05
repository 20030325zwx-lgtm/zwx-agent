package com.zwx.zwxagent.agent.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.agent.graph.ActivityEvent;
import com.zwx.zwxagent.agent.graph.GraphCheckpoint;
import com.zwx.zwxagent.agent.graph.PlanStep;
import com.zwx.zwxagent.chat.conversation.ApplicationInstanceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 超级智能体执行实例的持久化：run 元数据 + 图检查点 + 部分回答。
 * 检查点按节点写入 PostgreSQL，服务重启后可据此继续执行。
 */
@Slf4j
@Service
public class AgentRunService {

    private static final TypeReference<List<PlanStep>> PLAN_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ActivityEvent>> EVENT_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Integer>> INT_LIST_TYPE = new TypeReference<>() {
    };

    /**
     * 列表查询列清单：刻意排除 activities / sse_events（单个 run 可达数十 KB），
     * 会话列表、首页任务流只消费状态与进度摘要，避免每行都去解压 TOAST。
     */
    private static final String SLIM_COLUMNS = "id, conversation_id, tenant_id, user_id, agent_key, "
            + "user_message_id, assistant_message_id, status, phase, waiting_reason, waiting_detail, "
            + "background_jobs, background_deadline, prompt_versions, permission_mode, input, history_context, "
            + "knowledge_context, rag_trace_json, token_usage, pending_confirmation, last_node, next_node, plan, "
            + "task_results, revision, verification_status, verification_feedback, final_answer, partial_answer, "
            + "revise_steps, engine_version, error_message, resume_count, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ApplicationInstanceRegistry instanceRegistry;

    public AgentRunService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this(jdbcTemplate, objectMapper, null);
    }

    @Autowired
    public AgentRunService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                           ApplicationInstanceRegistry instanceRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.instanceRegistry = instanceRegistry;
    }

    public String start(String conversationId, String tenantId, String userId, String agentKey,
                        long userMessageId, String input, String historyContext,
                        String knowledgeContext, String ragTraceJson, String permissionMode) {
        String id = UUID.randomUUID().toString();
        String ownerInstance = instanceRegistry == null ? null : instanceRegistry.currentInstanceId();
        int inserted = jdbcTemplate.update("""
                INSERT INTO agent_run (id, conversation_id, tenant_id, user_id, agent_key, user_message_id,
                                       status, phase, permission_mode, input, history_context, knowledge_context, rag_trace_json,
                                       owner_instance)
                SELECT ?, c.id, ?, ?, ?, ?, 'RUNNING', 'WORKING', ?, ?, ?, ?, ?, ?
                FROM agent_conversation c
                WHERE c.id = ? AND c.tenant_id = ? AND c.user_id = ? AND c.agent_key = ?
                """, id, tenantId, userId, agentKey, userMessageId, permissionMode, input, historyContext,
                knowledgeContext, ragTraceJson, ownerInstance, conversationId, tenantId, userId, agentKey);
        if (inserted == 0) throw new IllegalArgumentException("Agent conversation was not found");
        startAssignment(id, 1, null);
        return id;
    }

    /** 记录 prompt 版本快照（run 开始时写入，事后可知道这次用了哪版提示词）。 */
    public void recordPromptVersions(String runId, String versionsJson) {
        if (runId == null || runId.isBlank() || versionsJson == null || versionsJson.isBlank()) return;
        jdbcTemplate.update("""
                UPDATE agent_run SET prompt_versions = CAST(? AS jsonb), updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, versionsJson, runId);
    }

    // ── 派工（Assignment）：一次执行绑定一行，重派/续跑都新建 ──────────────────

    public record AssignmentView(String id, int attempt, String executorType, String status,
                                 String sessionRef, String workDir, Instant startedAt, Instant endedAt) {
    }

    /** 开启一次派工：attempt 从 1 起，续跑时调用方传 resumeCount+1。 */
    public void startAssignment(String runId, int attempt, String workDir) {
        if (runId == null || runId.isBlank()) return;
        jdbcTemplate.update("""
                INSERT INTO agent_run_assignment (id, run_id, attempt, executor_type, work_dir, status)
                VALUES (?, ?, ?, 'local', ?, 'RUNNING')
                ON CONFLICT (run_id, attempt) DO UPDATE SET status = 'RUNNING', ended_at = NULL
                """, UUID.randomUUID().toString(), runId, Math.max(1, attempt), workDir);
    }

    /** 结束当前（最新）派工：DONE/FAILED/WAITING_HUMAN 各自落一段结果摘要。 */
    public void endAssignment(String runId, String summary) {
        if (runId == null || runId.isBlank()) return;
        jdbcTemplate.update("""
                UPDATE agent_run_assignment SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP,
                       result_summary = ?
                WHERE run_id = ? AND status = 'RUNNING'
                """, summary, runId);
    }

    public List<AssignmentView> assignments(String runId) {
        if (runId == null || runId.isBlank()) return List.of();
        return jdbcTemplate.query("""
                SELECT id, attempt, executor_type, status, session_ref, work_dir, started_at, ended_at
                FROM agent_run_assignment WHERE run_id = ? ORDER BY attempt
                """, (rs, rowNum) -> new AssignmentView(
                rs.getString("id"), rs.getInt("attempt"), rs.getString("executor_type"), rs.getString("status"),
                rs.getString("session_ref"), rs.getString("work_dir"),
                instant(rs, "started_at"), instant(rs, "ended_at")), runId);
    }

    public void checkpoint(String runId, GraphCheckpoint checkpoint) {
        if (runId == null || runId.isBlank() || checkpoint == null) return;
        log.debug("persist agent run checkpoint; runId={}, lastNode={}, nextNode={}", runId, checkpoint.lastNode(), checkpoint.nextNode());
        jdbcTemplate.update("""
                UPDATE agent_run SET
                    last_node = ?, next_node = ?, plan = CAST(? AS jsonb),
                    task_results = CAST(? AS jsonb), activities = CAST(? AS jsonb), sse_events = CAST(? AS jsonb),
                    revise_steps = CAST(? AS jsonb),
                    revision = ?, verification_status = ?, verification_feedback = ?,
                    final_answer = ?, partial_answer = CASE WHEN ? = '' THEN partial_answer ELSE ? END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                """, checkpoint.lastNode(), checkpoint.nextNode(), json(checkpoint.plan()),
                json(checkpoint.taskResults()), json(checkpoint.activities()), json(checkpoint.events()),
                json(checkpoint.reviseSteps()),
                checkpoint.revision(), checkpoint.verificationStatus(), checkpoint.verificationFeedback(),
                checkpoint.finalAnswer(), nullToEmpty(checkpoint.partialAnswer()), nullToEmpty(checkpoint.partialAnswer()),
                runId);
    }

    public void updatePartialAnswer(String runId, String partialAnswer) {
        if (runId == null || runId.isBlank() || partialAnswer == null || partialAnswer.isBlank()) return;
        jdbcTemplate.update("""
                UPDATE agent_run SET partial_answer = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                """, partialAnswer, runId);
    }

    /**
     * 落 token 账目。数值列累加（跨重启续跑的每一段都计入），token_usage 存最近一次的分解。
     */
    public void recordTokenUsage(String runId, String usageJson, int promptTokens, int completionTokens, int calls) {
        if (runId == null || runId.isBlank()) return;
        jdbcTemplate.update("""
                UPDATE agent_run SET
                    prompt_tokens = COALESCE(prompt_tokens, 0) + ?,
                    completion_tokens = COALESCE(completion_tokens, 0) + ?,
                    llm_calls = COALESCE(llm_calls, 0) + ?,
                    token_usage = COALESCE(CAST(? AS jsonb), token_usage),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, promptTokens, completionTokens, calls, usageJson, runId);
    }

    public void finish(String runId, String status, String answer, String error, Long assistantMessageId) {
        // 本轮结束时统计该 run 仍在跑的后台媒体任务：>0 时阶段保持 WORKING 并保留 slot（默认 30 分钟）
        int backgroundJobs = countActiveMediaJobs(runId);
        jdbcTemplate.update("""
                UPDATE agent_run SET status = ?, phase = CASE
                        WHEN waiting_reason = 'NEED_INPUT' THEN 'WAITING_HUMAN'
                        WHEN ? = 'COMPLETED' AND ? > 0 THEN 'WORKING'
                        WHEN ? = 'COMPLETED' THEN 'DONE'
                        WHEN ? = 'FAILED' THEN 'FAILED'
                        WHEN ? = 'INTERRUPTED' AND waiting_reason IS NOT NULL THEN 'WAITING_HUMAN'
                        WHEN ? = 'INTERRUPTED' THEN 'STOPPED'
                        ELSE phase END,
                    background_jobs = ?,
                    background_deadline = CASE WHEN ? > 0 THEN CURRENT_TIMESTAMP + INTERVAL '30 minutes' ELSE NULL END,
                    final_answer = COALESCE(?, final_answer),
                    partial_answer = COALESCE(?, partial_answer), error_message = ?,
                    assistant_message_id = COALESCE(?, assistant_message_id),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, status, status, backgroundJobs, status, status, status, status,
                backgroundJobs, backgroundJobs, answer, answer, error, assistantMessageId, runId);
        endAssignment(runId, status + (backgroundJobs > 0 ? " · 后台任务 " + backgroundJobs : ""));
    }

    private int countActiveMediaJobs(String runId) {
        if (runId == null || runId.isBlank()) return 0;
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM creative_media_job
                    WHERE source_run_id = ? AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                    """, Integer.class, runId);
            return count == null ? 0 : count;
        } catch (Exception exception) {
            // 媒体表不存在（裁剪部署）时按无后台任务处理
            return 0;
        }
    }

    public void restart(String runId) {
        String ownerInstance = instanceRegistry == null ? null : instanceRegistry.currentInstanceId();
        jdbcTemplate.update("""
                UPDATE agent_run SET status = 'RUNNING', phase = 'WORKING', error_message = NULL, owner_instance = ?,
                    pending_confirmation = NULL, waiting_reason = NULL, waiting_detail = NULL,
                    background_jobs = 0, background_deadline = NULL,
                    resume_count = resume_count + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, ownerInstance, runId);
        Integer nextAttempt = jdbcTemplate.queryForObject(
                "SELECT resume_count + 1 FROM agent_run WHERE id = ?", Integer.class, runId);
        startAssignment(runId, nextAttempt == null ? 1 : nextAttempt, null);
    }

    /** run 挂起等待工具确认：记录待确认清单（JSON），决定并恢复后由 restart 清空。 */
    public void markPendingConfirmation(String runId, String pendingJson) {
        if (runId == null || runId.isBlank() || pendingJson == null || pendingJson.isBlank()) return;
        jdbcTemplate.update("""
                UPDATE agent_run SET pending_confirmation = CAST(? AS jsonb),
                       phase = 'WAITING_HUMAN', waiting_reason = 'CONFIRM_TOOL',
                       waiting_detail = '有副作用工具等待确认',
                       updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, pendingJson, runId);
    }

    public void markInterrupted(String runId, String reason) {
        jdbcTemplate.update("""
                UPDATE agent_run SET status = 'INTERRUPTED', phase = 'STOPPED',
                       waiting_reason = NULL, waiting_detail = NULL,
                       error_message = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'RUNNING'
                """, reason, runId);
        endAssignment(runId, "INTERRUPTED: " + (reason == null ? "" : reason));
    }

    /** 计划确认门：计划已生成待授权，标记挂起状态供工作台展示计划卡片。 */
    public void markPlanPending(String runId) {
        if (runId == null || runId.isBlank()) return;
        jdbcTemplate.update("""
                UPDATE agent_run SET status = 'INTERRUPTED', phase = 'WAITING_HUMAN',
                                      waiting_reason = 'CONFIRM_PLAN',
                                      waiting_detail = '多步计划已生成，等待确认后执行',
                                      verification_status = 'plan_pending',
                                      error_message = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, runId);
        endAssignment(runId, "WAITING_HUMAN: CONFIRM_PLAN");
    }

    /**
     * 智能体通过 askHuman 提问：run 进入「等待补充信息」，问题原文存在 waiting_detail，
     * 面板/对话流据此渲染可回答的卡片（不靠自由文本推断）。
     */
    public void markWaitingInput(String runId, String question) {
        if (runId == null || runId.isBlank()) return;
        jdbcTemplate.update("""
                UPDATE agent_run SET phase = 'WAITING_HUMAN', waiting_reason = 'NEED_INPUT',
                       waiting_detail = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, question == null ? "" : question, runId);
    }

    /** 用户已回答提问：清空等待状态（答复本身作为新消息/新 run 继续，老 run 收口）。 */
    public void markInputAnswered(String runId) {
        if (runId == null || runId.isBlank()) return;
        jdbcTemplate.update("""
                UPDATE agent_run SET phase = CASE WHEN phase = 'WAITING_HUMAN' THEN 'DONE' ELSE phase END,
                       waiting_reason = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, runId);
        endAssignment(runId, "DONE: 用户已答复提问");
    }

    /** 后台任务扫尾：仍有任务则保持 WORKING；超过保留时限置 BLOCKED；清零则 DONE。 */
    public int sweepBackgroundRuns() {
        return jdbcTemplate.update("""
                UPDATE agent_run r SET
                    background_jobs = sub.cnt,
                    phase = CASE
                        WHEN sub.cnt > 0 AND r.background_deadline > CURRENT_TIMESTAMP THEN 'WORKING'
                        WHEN sub.cnt > 0 THEN 'BLOCKED'
                        ELSE 'DONE' END,
                    waiting_reason = CASE WHEN sub.cnt > 0 AND r.background_deadline <= CURRENT_TIMESTAMP
                                          THEN 'NEED_INPUT' ELSE r.waiting_reason END,
                    waiting_detail = CASE WHEN sub.cnt > 0 AND r.background_deadline <= CURRENT_TIMESTAMP
                                          THEN '后台任务超过保留时限仍未结束，请检查生成任务或稍后重试'
                                          ELSE r.waiting_detail END,
                    updated_at = CURRENT_TIMESTAMP
                FROM (SELECT r2.id,
                             (SELECT count(*) FROM creative_media_job j
                              WHERE j.source_run_id = r2.id
                                AND j.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')) AS cnt
                      FROM agent_run r2
                      WHERE r2.background_jobs > 0) sub
                WHERE r.id = sub.id
                """);
    }

    public void bindAssistantMessage(String runId, long assistantMessageId) {
        jdbcTemplate.update("UPDATE agent_run SET assistant_message_id = ? WHERE id = ?", assistantMessageId, runId);
    }

    public Optional<AgentRun> findScoped(String tenantId, String userId, String conversationId, String runId) {
        return jdbcTemplate.query("""
                SELECT * FROM agent_run
                WHERE id = ? AND tenant_id = ? AND user_id = ? AND conversation_id = ? AND agent_key = 'super'
                """, runMapper(), runId, tenantId, userId, conversationId).stream().findFirst();
    }

    /** 中台只读查询：按租户校验（跨用户），仅供内部令牌端点使用。 */
    public Optional<AgentRun> findByIdInTenant(String tenantId, String runId) {
        return jdbcTemplate.query("""
                SELECT * FROM agent_run WHERE id = ? AND tenant_id = ? AND agent_key = 'super'
                """, runMapper(), runId, tenantId).stream().findFirst();
    }

    public List<AgentRun> findScopedRuns(String tenantId, String userId, String conversationId, int limit) {
        return jdbcTemplate.query("SELECT " + SLIM_COLUMNS + " FROM agent_run"
                        + " WHERE tenant_id = ? AND user_id = ? AND conversation_id = ? AND agent_key = 'super'"
                        + " ORDER BY created_at DESC, updated_at DESC LIMIT ?",
                (rs, rowNum) -> readRun(rs, true), tenantId, userId, conversationId, Math.max(1, limit));
    }

    /** 跨会话的最近任务（首页任务流）：按更新时间倒序，过滤无输入的空 run。 */
    public List<AgentRun> findRecentRuns(String tenantId, String userId, int limit) {
        return jdbcTemplate.query("SELECT " + SLIM_COLUMNS + " FROM agent_run"
                        + " WHERE tenant_id = ? AND user_id = ? AND agent_key = 'super' AND input IS NOT NULL AND input <> ''"
                        + " ORDER BY updated_at DESC LIMIT ?",
                (rs, rowNum) -> readRun(rs, true), tenantId, userId, Math.max(1, limit));
    }

    /** 工作台概览条目：带会话标题与任务输入，供跨会话任务动态与状态统计展示。 */
    public record RunOverview(String id, String conversationId, String status, String input,
                              String conversationTitle, java.time.Instant createdAt, java.time.Instant updatedAt) {
    }

    public List<RunOverview> findRecentRunOverviews(String tenantId, String userId, int limit) {
        return jdbcTemplate.query("""
                SELECT r.id, r.conversation_id, r.status, r.input, r.created_at, r.updated_at,
                       COALESCE(c.title, '') AS conversation_title
                FROM agent_run r
                LEFT JOIN agent_conversation c ON c.id = r.conversation_id
                WHERE r.tenant_id = ? AND r.user_id = ? AND r.agent_key = 'super'
                  AND r.input IS NOT NULL AND r.input <> ''
                ORDER BY r.updated_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new RunOverview(
                        rs.getString("id"), rs.getString("conversation_id"), rs.getString("status"),
                        rs.getString("input"), rs.getString("conversation_title"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                tenantId, userId, Math.max(1, limit));
    }

    /** 中台轨迹浏览条目：租户内跨用户，带答案与 token，仅内部令牌端点使用。 */
    public record TenantRunOverview(String id, String conversationId, String conversationTitle, String userId,
                                    String status, String input, String answer,
                                    Integer promptTokens, Integer completionTokens,
                                    java.time.Instant createdAt, java.time.Instant updatedAt) {
    }

    public List<TenantRunOverview> findTenantRunOverviews(String tenantId, int limit) {
        return jdbcTemplate.query("""
                SELECT r.id, r.conversation_id, r.status, r.input, r.user_id,
                       COALESCE(c.title, '') AS conversation_title,
                       COALESCE(r.final_answer, r.partial_answer, '') AS answer,
                       COALESCE(r.prompt_tokens, 0) AS prompt_tokens,
                       COALESCE(r.completion_tokens, 0) AS completion_tokens,
                       r.created_at, r.updated_at
                FROM agent_run r
                LEFT JOIN agent_conversation c ON c.id = r.conversation_id
                WHERE r.tenant_id = ? AND r.agent_key = 'super'
                  AND r.input IS NOT NULL AND r.input <> ''
                ORDER BY r.updated_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new TenantRunOverview(
                        rs.getString("id"), rs.getString("conversation_id"), rs.getString("conversation_title"),
                        rs.getString("user_id"), rs.getString("status"), rs.getString("input"),
                        rs.getString("answer"), rs.getInt("prompt_tokens"), rs.getInt("completion_tokens"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                tenantId, Math.min(Math.max(limit, 1), 100));
    }

    public Optional<AgentRun> findLatestResumable(String tenantId, String userId, String conversationId) {        return jdbcTemplate.query("""
                SELECT * FROM agent_run
                WHERE tenant_id = ? AND user_id = ? AND conversation_id = ? AND agent_key = 'super'
                  AND status IN ('INTERRUPTED', 'FAILED')
                ORDER BY updated_at DESC LIMIT 1
                """, runMapper(), tenantId, userId, conversationId).stream().findFirst();
    }

    public List<AgentRun> findOrphanedRunning() {
        return jdbcTemplate.query("""
                SELECT r.* FROM agent_run r
                WHERE r.status = 'RUNNING'
                  AND (
                      (r.owner_instance IS NOT NULL AND NOT EXISTS (
                          SELECT 1 FROM agent_instance i
                          WHERE i.instance_id = r.owner_instance
                            AND i.heartbeat_at > CURRENT_TIMESTAMP - INTERVAL '15 seconds'
                      ))
                      OR (r.owner_instance IS NULL AND NOT EXISTS (
                          SELECT 1 FROM agent_conversation_lock l
                          WHERE l.conversation_id = r.conversation_id AND l.expires_at > CURRENT_TIMESTAMP
                      ))
                  )
                ORDER BY r.created_at
                """, runMapper());
    }

    private RowMapper<AgentRun> runMapper() {
        return (rs, rowNum) -> readRun(rs);
    }

    private AgentRun readRun(ResultSet rs) throws SQLException {
        return readRun(rs, false);
    }

    /** slim=true 时不读取 activities / sse_events（列表查询未选这两列），按空列表填充。 */
    private AgentRun readRun(ResultSet rs, boolean slim) throws SQLException {
        GraphCheckpoint checkpoint = new GraphCheckpoint(
                rs.getString("last_node"),
                rs.getString("next_node"),
                read(rs.getString("plan"), PLAN_TYPE),
                read(rs.getString("task_results"), STRING_LIST_TYPE),
                slim ? List.of() : read(rs.getString("activities"), STRING_LIST_TYPE),
                slim ? List.of() : read(rs.getString("sse_events"), EVENT_LIST_TYPE),
                rs.getInt("revision"),
                rs.getString("verification_status"),
                rs.getString("verification_feedback"),
                rs.getString("final_answer"),
                rs.getString("partial_answer"),
                read(rs.getString("revise_steps"), INT_LIST_TYPE));
        long assistantMessageId = rs.getLong("assistant_message_id");
        Long assistantId = rs.wasNull() ? null : assistantMessageId;
        return new AgentRun(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("agent_key"),
                rs.getLong("user_message_id"),
                assistantId,
                rs.getString("status"),
                rs.getString("phase"),
                rs.getString("waiting_reason"),
                rs.getString("waiting_detail"),
                rs.getInt("background_jobs"),
                instant(rs, "background_deadline"),
                rs.getString("prompt_versions"),
                rs.getString("permission_mode"),
                rs.getString("input"),
                rs.getString("history_context"),
                rs.getString("knowledge_context"),
                rs.getString("rag_trace_json"),
                rs.getString("token_usage"),
                rs.getString("pending_confirmation"),
                checkpoint,
                rs.getInt("engine_version"),
                rs.getString("error_message"),
                rs.getInt("resume_count"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private <T> List<T> read(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read persisted agent run checkpoint", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize agent run checkpoint", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
