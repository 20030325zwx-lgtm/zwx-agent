package com.zwx.zwxagent.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AgentExecutionTraceService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentExecutionTraceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public int record(String runId, String tenantId, String agentKey, String conversationId, String phase, String summary, Map<String, Object> detail) {
        String json;
        try {
            json = objectMapper.writeValueAsString(detail);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize execution event detail", exception);
        }
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Integer sequence = jdbcTemplate.queryForObject("""
                        INSERT INTO agent_execution_event (run_id, tenant_id, agent_key, conversation_id, sequence, phase, summary, detail)
                        SELECT ?, ?, ?, ?, COALESCE(MAX(sequence), 0) + 1, ?, ?, CAST(? AS jsonb)
                        FROM agent_execution_event WHERE run_id = ?
                        RETURNING sequence
                        """, Integer.class, runId, tenantId, agentKey, conversationId, phase, summary, json, runId);
                return sequence == null ? 0 : sequence;
            } catch (org.springframework.dao.DataIntegrityViolationException exception) {
                lastFailure = exception;
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to persist execution event", exception);
            }
        }
        throw new IllegalStateException("Unable to persist execution event after retries", lastFailure);
    }

    public List<AgentExecutionEvent> listTravelEvents(String tenantId, String conversationId, String runId) {
        return jdbcTemplate.query("""
                SELECT sequence, phase, summary, detail, created_at FROM agent_execution_event
                WHERE run_id = ? AND tenant_id = ? AND agent_key = 'travel' AND conversation_id = ? ORDER BY sequence
                """, (rs, rowNum) -> new AgentExecutionEvent(rs.getInt("sequence"), rs.getString("phase"), rs.getString("summary"),
                readDetail(rs.getString("detail")), rs.getTimestamp("created_at").toInstant()), runId, tenantId, conversationId);
    }

    public void deleteRun(String tenantId, String conversationId, String runId) {
        jdbcTemplate.update("DELETE FROM agent_execution_event WHERE run_id = ? AND tenant_id = ? AND conversation_id = ?", runId, tenantId, conversationId);
    }

    private Map<String, Object> readDetail(String detail) {
        try { return objectMapper.readValue(detail, new TypeReference<>() {}); }
        catch (Exception exception) { throw new IllegalStateException("Unable to read execution event", exception); }
    }
}
