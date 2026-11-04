package com.zwx.zwxagent.agent.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AgentExecutionTraceService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentExecutionTraceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public int record(String runId, String tenantId, String agentKey, String conversationId, String phase, String summary, Map<String, Object> detail) {
        return record(runId, tenantId, null, agentKey, conversationId, phase, summary, detail, null, null);
    }

    /**
     * 持久化一条结构化执行事件（整改 A2，方案 §5.1）：
     * run 行存在时序号由 agent_run.event_seq 原子递增分配（同事务写业务状态与事件，无 MAX+1 竞争）；
     * 无 run 行的历史写入方退回原 MAX+1 路径。dedupeKey 非空时同 run 内幂等（重复投递只落一条）。
     *
     * @return 分配到的事件序号（幂等命中时为既有序号 -1 语义：返回 0 且不新增行）
     */
    public int record(String runId, String tenantId, String userId, String agentKey, String conversationId,
                      String phase, String summary, Map<String, Object> detail, String eventType, String dedupeKey) {
        String json;
        try {
            json = objectMapper.writeValueAsString(detail);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize execution event detail", exception);
        }
        // 1) 优先走 run 行上的原子序号分配器
        try {
            Long sequence = jdbcTemplate.queryForObject(
                    "UPDATE agent_run SET event_seq = event_seq + 1 WHERE id = ? RETURNING event_seq",
                    Long.class, runId);
            if (sequence != null) {
                int updated = jdbcTemplate.update("""
                        INSERT INTO agent_execution_event (run_id, tenant_id, agent_key, conversation_id, sequence,
                            phase, summary, detail, user_id, event_type, schema_version, dedupe_key)
                        VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, 1, ?)
                        ON CONFLICT (run_id, dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING
                        """, runId, tenantId, agentKey, conversationId, sequence, phase, summary, json,
                        userId, eventType, dedupeKey);
                if (updated == 0 && dedupeKey != null) {
                    // 幂等命中：回滚序号占用，保持游标连续性由读取端按实际行判断
                    jdbcTemplate.update("UPDATE agent_run SET event_seq = event_seq - 1 WHERE id = ?", runId);
                    logSkipped(runId, dedupeKey);
                }
                return sequence.intValue();
            }
        } catch (org.springframework.dao.EmptyResultDataAccessException noRunRow) {
            // 无 agent_run 行的旧写入方，走兼容路径
        }
        // 2) 兼容路径：MAX+1 + 唯一冲突重试（历史行为）
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Integer sequence = jdbcTemplate.queryForObject("""
                        INSERT INTO agent_execution_event (run_id, tenant_id, agent_key, conversation_id, sequence, phase, summary, detail, user_id, event_type)
                        SELECT ?, ?, ?, ?, COALESCE(MAX(sequence), 0) + 1, ?, ?, CAST(? AS jsonb), ?, ?
                        FROM agent_execution_event WHERE run_id = ?
                        RETURNING sequence
                        """, Integer.class, runId, tenantId, agentKey, conversationId, phase, summary, json, userId, eventType, runId);
                return sequence == null ? 0 : sequence;
            } catch (org.springframework.dao.DataIntegrityViolationException exception) {
                lastFailure = exception;
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to persist execution event", exception);
            }
        }
        throw new IllegalStateException("Unable to persist execution event after retries", lastFailure);
    }

    private void logSkipped(String runId, String dedupeKey) {
        log.debug("[trace] 幂等命中，跳过重复事件；runId={}, dedupeKey={}", runId, dedupeKey);
    }

    /** 分页读取持久化事件（keyset 游标），供 GET /events 与 SSE 网关回放。 */
    public List<PersistedEvent> listAfter(String runId, String tenantId, String userId, long afterSeq, int limit) {
        return jdbcTemplate.query("""
                SELECT sequence, phase, summary, detail, event_type, schema_version, step_id, tool_call_id, created_at
                FROM agent_execution_event
                WHERE run_id = ? AND tenant_id = ? AND user_id = ? AND sequence > ?
                ORDER BY sequence LIMIT ?
                """, (rs, rowNum) -> new PersistedEvent(rs.getLong("sequence"), rs.getString("phase"),
                rs.getString("summary"), readDetail(rs.getString("detail")), rs.getString("event_type"),
                rs.getInt("schema_version"),
                rs.getObject("step_id") == null ? null : rs.getObject("step_id").toString(),
                rs.getObject("tool_call_id") == null ? null : rs.getLong("tool_call_id"),
                rs.getTimestamp("created_at").toInstant()),
                runId, tenantId, userId, afterSeq, Math.max(1, Math.min(limit, 500)));
    }

    public record PersistedEvent(long sequence, String phase, String summary, Map<String, Object> detail,
                                 String eventType, int schemaVersion, String stepId, Long toolCallId,
                                 Instant createdAt) {
    }

    public List<AgentExecutionEvent> listTravelEvents(String tenantId, String conversationId, String runId) {
        return listEvents(tenantId, "travel", conversationId, runId);
    }

    /** 通用执行轨迹查询（tenant 隔离）；listTravelEvents 委托复用。 */
    public List<AgentExecutionEvent> listEvents(String tenantId, String agentKey, String conversationId, String runId) {
        return jdbcTemplate.query("""
                SELECT sequence, phase, summary, detail, created_at FROM agent_execution_event
                WHERE run_id = ? AND tenant_id = ? AND agent_key = ? AND conversation_id = ? ORDER BY sequence
                """, (rs, rowNum) -> new AgentExecutionEvent(rs.getInt("sequence"), rs.getString("phase"), rs.getString("summary"),
                readDetail(rs.getString("detail")), rs.getTimestamp("created_at").toInstant()), runId, tenantId, agentKey, conversationId);
    }

    /** 按会话查最近事件（不限 run），供会话级时间线展示。 */
    public List<AgentExecutionEvent> listRecentEvents(String tenantId, String agentKey, String conversationId, int limit) {
        return jdbcTemplate.query("""
                SELECT sequence, phase, summary, detail, created_at FROM agent_execution_event
                WHERE tenant_id = ? AND agent_key = ? AND conversation_id = ?
                ORDER BY id DESC LIMIT ?
                """, (rs, rowNum) -> new AgentExecutionEvent(rs.getInt("sequence"), rs.getString("phase"), rs.getString("summary"),
                readDetail(rs.getString("detail")), rs.getTimestamp("created_at").toInstant()), tenantId, agentKey, conversationId, limit);
    }

    public void deleteRun(String tenantId, String conversationId, String runId) {
        jdbcTemplate.update("DELETE FROM agent_execution_event WHERE run_id = ? AND tenant_id = ? AND conversation_id = ?", runId, tenantId, conversationId);
    }

    private Map<String, Object> readDetail(String detail) {
        try { return objectMapper.readValue(detail, new TypeReference<>() {}); }
        catch (Exception exception) { throw new IllegalStateException("Unable to read execution event", exception); }
    }
}
