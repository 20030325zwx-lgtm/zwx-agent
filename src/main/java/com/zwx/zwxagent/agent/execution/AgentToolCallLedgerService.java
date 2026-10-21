package com.zwx.zwxagent.agent.execution;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 工具调用幂等账本：
 * - 已成功调用直接复用结果；
 * - 中断时仍是 PENDING 的调用标记为 UNKNOWN，恢复时禁止盲目重放；
 * - 其他失败调用保留失败结果，避免恢复时重复产生副作用。
 */
@Service
public class AgentToolCallLedgerService {

    private static final int MAX_RESULT_CHARS = 200_000;

    private final JdbcTemplate jdbcTemplate;

    public AgentToolCallLedgerService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Decision begin(String runId, String toolName, String arguments) {
        String safeArguments = arguments == null ? "" : arguments;
        String hash = sha256(safeArguments);
        Long id = insertPending(runId, toolName, hash, safeArguments);
        if (id != null) {
            return new Decision(Mode.EXECUTE, id, null, null);
        }
        return readExisting(runId, toolName, hash);
    }

    private Long insertPending(String runId, String toolName, String hash, String arguments) {
        try {
            return jdbcTemplate.queryForObject("""
                    INSERT INTO agent_tool_call (run_id, tool_name, arguments_hash, arguments, status)
                    VALUES (?, ?, ?, ?, 'PENDING')
                    ON CONFLICT (run_id, tool_name, arguments_hash) DO NOTHING
                    RETURNING id
                    """, Long.class, runId, toolName, hash, arguments);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    private Decision readExisting(String runId, String toolName, String hash) {
        return jdbcTemplate.query("""
                SELECT id, status, result FROM agent_tool_call
                WHERE run_id = ? AND tool_name = ? AND arguments_hash = ?
                """, (rs, rowNum) -> {
                    long id = rs.getLong("id");
                    String status = rs.getString("status");
                    String result = rs.getString("result");
                    if ("SUCCEEDED".equals(status)) {
                        return new Decision(Mode.REPLAY, id, result, null);
                    }
                    if ("PENDING".equals(status)) {
                        jdbcTemplate.update("""
                                UPDATE agent_tool_call SET status = 'UNKNOWN', updated_at = CURRENT_TIMESTAMP
                                WHERE id = ? AND status = 'PENDING'
                                """, id);
                        return new Decision(Mode.UNKNOWN, id, null,
                                "上一次工具调用在上次执行中断时结果未知，已阻止重复执行。"
                                        + "请向用户说明该操作可能已经发生，不要再次调用同一工具。");
                    }
                    if ("UNKNOWN".equals(status)) {
                        return new Decision(Mode.UNKNOWN, id, null,
                                "上一次工具调用结果未知，已阻止重复执行。"
                                        + "请向用户说明该操作可能已经发生，不要再次调用同一工具。");
                    }
                    return new Decision(Mode.REPLAY, id, result,
                            "该工具调用上次执行失败，已返回上次失败结果，避免重复产生副作用。");
                }, runId, toolName, hash).stream().findFirst()
                .orElseGet(() -> new Decision(Mode.EXECUTE, null, null, null));
    }

    public void succeed(Long id, String result) {
        if (id == null) return;
        jdbcTemplate.update("""
                UPDATE agent_tool_call SET status = 'SUCCEEDED', result = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING'
                """, truncate(result), id);
    }

    public void fail(Long id, String result) {
        if (id == null) return;
        jdbcTemplate.update("""
                UPDATE agent_tool_call SET status = 'FAILED', result = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING'
                """, truncate(result), id);
    }

    private String truncate(String value) {
        if (value == null) return "";
        return value.length() <= MAX_RESULT_CHARS
                ? value
                : value.substring(0, MAX_RESULT_CHARS) + "\n...[工具结果已截断用于幂等账本]";
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash tool arguments", exception);
        }
    }

    public enum Mode {
        EXECUTE, REPLAY, UNKNOWN
    }

    public record Decision(Mode mode, Long id, String cachedResult, String message) {
        public static Decision execute(Long id) {
            return new Decision(Mode.EXECUTE, id, null, null);
        }

        public static Decision replay(String result) {
            return new Decision(Mode.REPLAY, null, result, null);
        }

        public static Decision unknown(String message) {
            return new Decision(Mode.UNKNOWN, null, null, message);
        }
    }
}
