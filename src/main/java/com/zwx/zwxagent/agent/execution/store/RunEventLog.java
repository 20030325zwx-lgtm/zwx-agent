package com.zwx.zwxagent.agent.execution.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * run 事件日志（append-only 真相层）：恢复/审计/修尾的来源。
 * <p>
 * 融合治理约定：
 * <ul>
 *   <li>先落日志（真相），后打快照（缓存）；日志写失败仅降级为 warn，绝不阻断主流程</li>
 *   <li>seq 在锁 agent_run 行后取 MAX+1，双实例并发安全</li>
 *   <li>事件类型约束：成对事件（step/tool）必须闭合，恢复时未闭合尾部由 {@link #repairUnclosedToolCalls} 合成修复事件</li>
 * </ul>
 */
@Slf4j
@Service
public class RunEventLog {

    public static final String RUN_START = "run/start";
    public static final String RUN_END = "run/end";
    public static final String STEP_START = "step/start";
    public static final String STEP_END = "step/end";
    public static final String TOOL_CALL = "tool/call";
    public static final String TOOL_RESULT = "tool/result";
    public static final String TOOL_REPAIR = "tool/repair";
    public static final String ASSISTANT_ATTEMPT = "assistant/attempt";

    public record EventRow(long seq, String type, String data) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public RunEventLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条事件：事务内锁 agent_run 行后取 MAX+1 分配 seq（同 run 串行化，双实例安全）。
     * 写入失败仅记 warn，绝不阻断主流程。
     */
    @org.springframework.transaction.annotation.Transactional
    public void append(String runId, String tenantId, String userId, String type, Map<String, Object> data) {
        if (runId == null || runId.isBlank() || tenantId == null || userId == null) return;
        try {
            String dataJson = writeJson(data == null ? Map.of() : data);
            // 行锁串行化同 run 的 seq 分配（SELECT..FOR UPDATE 走 queryForList：JdbcTemplate.update 只收 DML）；
            // run 不存在时返回空，随后 INSERT 走不到，静默跳过
            jdbc.queryForList("SELECT id FROM agent_run WHERE id = ? AND tenant_id = ? AND user_id = ? FOR UPDATE",
                    runId, tenantId, userId);
            jdbc.update("""
                    INSERT INTO agent_run_event (id, tenant_id, user_id, run_id, seq, type, data)
                    SELECT ?, ?, ?, ?,
                           COALESCE((SELECT MAX(e.seq) FROM agent_run_event e WHERE e.run_id = ?), 0) + 1,
                           ?, ?::jsonb
                    FROM agent_run r
                    WHERE r.id = ? AND r.tenant_id = ? AND r.user_id = ?
                    """, UUID.randomUUID(), tenantId, userId, runId, runId, type, dataJson,
                    runId, tenantId, userId);
        } catch (Exception exception) {
            log.warn("run 事件写入失败（type={}，runId={}）：{}", type, runId, exception.getMessage());
        }
    }

    /** 当前 run 的日志尾部序号（快照 last_event_seq 取值）；无日志返回 0。 */
    public long lastSeq(String runId, String tenantId, String userId) {
        if (runId == null || runId.isBlank() || tenantId == null || userId == null) return 0;
        try {
            Long seq = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(seq), 0) FROM agent_run_event WHERE run_id = ?
                    """, Long.class, runId);
            return seq == null ? 0 : seq;
        } catch (Exception exception) {
            log.warn("run 事件序号读取失败；runId={}：{}", runId, exception.getMessage());
            return 0;
        }
    }

    /** run 的日志尾部（按 seq 降序最多 limit 条，返回按 seq 升序）；读失败返回空。 */
    public List<EventRow> tail(String runId, int limit) {
        if (runId == null || runId.isBlank()) return List.of();
        try {
            List<EventRow> rows = jdbc.query("""
                    SELECT seq, type, data::text FROM agent_run_event
                    WHERE run_id = ? ORDER BY seq DESC LIMIT ?
                    """, (rs, index) -> new EventRow(rs.getLong("seq"), rs.getString("type"), rs.getString("data")),
                    runId, Math.max(1, limit));
            List<EventRow> ordered = new ArrayList<>(rows);
            java.util.Collections.reverse(ordered);
            return ordered;
        } catch (Exception exception) {
            log.warn("run 事件读取失败；runId={}：{}", runId, exception.getMessage());
            return List.of();
        }
    }

    /**
     * 修尾：扫描日志尾部，为没有 result 事件的 tool/call 合成 {@link #TOOL_REPAIR} 事件（模型可见措辞）。
     *
     * @return 面向用户/模型的修复摘要（每条未闭合调用一行）；无未闭合调用返回空列表
     */
    public List<String> repairUnclosedToolCalls(String runId, String tenantId, String userId) {
        List<EventRow> tailEvents = tail(runId, 300);
        Map<String, String> openCalls = new LinkedHashMap<>();
        for (EventRow event : tailEvents) {
            Map<String, Object> data = readJson(event.data());
            String callId = String.valueOf(data.getOrDefault("callId", ""));
            if (TOOL_CALL.equals(event.type()) && !callId.isBlank()) {
                openCalls.put(callId, String.valueOf(data.getOrDefault("tool", "?")));
            } else if (TOOL_RESULT.equals(event.type()) && !callId.isBlank()) {
                openCalls.remove(callId);
            } else if (TOOL_REPAIR.equals(event.type()) && !callId.isBlank()) {
                openCalls.remove(callId);
            }
        }
        if (openCalls.isEmpty()) return List.of();
        List<String> notes = new ArrayList<>();
        for (Map.Entry<String, String> entry : openCalls.entrySet()) {
            String note = "工具 " + entry.getValue() + " 的调用在运行中断前未记录到结果，结果未知；"
                    + "重试前请先判断操作性质：只读或幂等可直接重试，可能有副作用的先核实外部状态。";
            append(runId, tenantId, userId, TOOL_REPAIR, Map.of(
                    "callId", entry.getKey(), "tool", entry.getValue(), "summary", note));
            notes.add(note);
            log.info("run 修尾：已合成未闭合工具调用修复事件；runId={}, tool={}", runId, entry.getValue());
        }
        return notes;
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception cause) {
            throw new IllegalArgumentException("run 事件 JSON 序列化失败", cause);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        try {
            return mapper.readValue(json == null || json.isBlank() ? "{}" : json, Map.class);
        } catch (Exception exception) {
            return Map.of();
        }
    }
}
