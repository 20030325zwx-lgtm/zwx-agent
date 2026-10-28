package com.zwx.zwxagent.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Run 轨迹导出：把检查点、工具账本、模型调用与执行事件聚合成一份可回放、可评分的证据。
 * 供评测留证与前端后续可视化使用。
 */
@Service
public class RunTrajectoryService {

    private final AgentRunService agentRunService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RunTrajectoryService(AgentRunService agentRunService, JdbcTemplate jdbcTemplate,
                                ObjectMapper objectMapper) {
        this.agentRunService = agentRunService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<Map<String, Object>> trajectory(String tenantId, String userId,
                                                    String conversationId, String runId) {
        Optional<AgentRun> found = agentRunService.findScoped(tenantId, userId, conversationId, runId);
        if (found.isEmpty()) return Optional.empty();
        AgentRun run = found.get();

        Map<String, Object> runView = new LinkedHashMap<>();
        runView.put("id", run.id());
        runView.put("conversationId", run.conversationId());
        runView.put("status", run.status());
        runView.put("permissionMode", run.permissionMode());
        runView.put("input", run.input());
        runView.put("errorMessage", run.errorMessage());
        runView.put("resumeCount", run.resumeCount());
        runView.put("tokenUsage", readJson(run.tokenUsageJson()));
        runView.put("createdAt", run.createdAt());
        runView.put("updatedAt", run.updatedAt());

        Map<String, Object> checkpoint = new LinkedHashMap<>();
        if (run.checkpoint() != null) {
            checkpoint.put("lastNode", run.checkpoint().lastNode());
            checkpoint.put("nextNode", run.checkpoint().nextNode());
            checkpoint.put("plan", run.checkpoint().plan());
            checkpoint.put("taskResults", run.checkpoint().taskResults());
            checkpoint.put("activities", run.checkpoint().activities());
            checkpoint.put("revision", run.checkpoint().revision());
            checkpoint.put("verificationStatus", run.checkpoint().verificationStatus());
            checkpoint.put("verificationFeedback", run.checkpoint().verificationFeedback());
            checkpoint.put("reviseSteps", run.checkpoint().reviseSteps());
            checkpoint.put("finalAnswer", run.checkpoint().finalAnswer());
            checkpoint.put("partialAnswer", run.checkpoint().partialAnswer());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", runView);
        result.put("checkpoint", checkpoint);
        result.put("toolCalls", jdbcTemplate.query("""
                SELECT tool_name, arguments_hash, status, result, created_at
                FROM agent_tool_call WHERE run_id = ? ORDER BY id
                """, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool", rs.getString("tool_name"));
            item.put("argumentsHash", rs.getString("arguments_hash"));
            item.put("status", rs.getString("status"));
            item.put("result", rs.getString("result"));
            item.put("createdAt", rs.getTimestamp("created_at"));
            return item;
        }, runId));
        result.put("llmCalls", jdbcTemplate.query("""
                SELECT sequence, phase, model, prompt_tokens, completion_tokens, latency_ms,
                       request_digest::text AS request_digest, response_digest::text AS response_digest, created_at
                FROM agent_llm_call WHERE run_id = ? ORDER BY id
                """, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sequence", rs.getInt("sequence"));
            item.put("phase", rs.getString("phase"));
            item.put("model", rs.getString("model"));
            item.put("promptTokens", rs.getInt("prompt_tokens"));
            item.put("completionTokens", rs.getInt("completion_tokens"));
            item.put("latencyMs", rs.getLong("latency_ms"));
            item.put("request", readJson(rs.getString("request_digest")));
            item.put("response", readJson(rs.getString("response_digest")));
            item.put("createdAt", rs.getTimestamp("created_at"));
            return item;
        }, runId));
        result.put("executionEvents", jdbcTemplate.query("""
                SELECT sequence, phase, summary, detail::text AS detail, created_at
                FROM agent_execution_event WHERE run_id = ? ORDER BY sequence
                """, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sequence", rs.getInt("sequence"));
            item.put("phase", rs.getString("phase"));
            item.put("summary", rs.getString("summary"));
            item.put("detail", readJson(rs.getString("detail")));
            item.put("createdAt", rs.getTimestamp("created_at"));
            return item;
        }, runId));
        return Optional.of(result);
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return json;
        }
    }
}
