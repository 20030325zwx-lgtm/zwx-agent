package com.zwx.zwxagent.agent.execution;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 工具调用幂等账本：
 * - 已成功调用直接复用结果；
 * - 中断时仍是 PENDING 的调用标记为 UNKNOWN，恢复时禁止盲目重放；
 * - 其他失败调用保留失败结果，避免恢复时重复产生副作用；
 * - A2 新增 invocation 路径（方案 §5.2）：一次逻辑调用以 invocation_key 为身份，重放不变；
 *   在途（PENDING）冲突进入有界等待而非立即误判 UNKNOWN。
 */
@Service
public class AgentToolCallLedgerService {

    private static final int MAX_RESULT_CHARS = 200_000;
    private static final int IN_FLIGHT_POLL_ATTEMPTS = 20;
    private static final long IN_FLIGHT_POLL_INTERVAL_MS = 250;

    private final JdbcTemplate jdbcTemplate;

    public AgentToolCallLedgerService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Decision begin(String runId, String toolName, String arguments) {
        return begin(runId, "", toolName, arguments);
    }

    public Decision begin(String runId, String workerKey, String toolName, String arguments) {
        String safeArguments = arguments == null ? "" : arguments;
        String hash = sha256(safeArguments);
        String key = workerKey == null ? "" : workerKey;
        Long id = insertPending(runId, key, toolName, hash, safeArguments);
        if (id != null) {
            return new Decision(Mode.EXECUTE, id, null, null);
        }
        return readExisting(runId, key, toolName, hash);
    }

    /**
     * invocation 身份路径（新引擎写入方）：同一逻辑调用重放/恢复时键不变，自动复用结果；
     * 并发在途等待至多 ~5 秒，仍无法确认则返回 UNKNOWN 但【不】改写账本行（区别于旧路径）。
     * V33 唯一约束仍在（同一 worker 内相同参数的调用会命中旧键）：此时回退读取既有行，
     * 保持与旧引擎一致的复用语义，直至旧约束在清理批次删除。
     */
    public Decision beginInvocation(String runId, java.util.UUID stepId, String invocationKey,
                                    String workerKey, String toolName, String arguments) {
        String safeArguments = arguments == null ? "" : arguments;
        String hash = sha256(safeArguments);
        String key = workerKey == null ? "" : workerKey;
        Long id = null;
        try {
            id = jdbcTemplate.queryForObject("""
                    INSERT INTO agent_tool_call (run_id, worker_key, tool_name, arguments_hash, arguments,
                        status, invocation_key, step_id)
                    VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)
                    ON CONFLICT (run_id, invocation_key) WHERE invocation_key IS NOT NULL DO NOTHING
                    RETURNING id
                    """, Long.class, runId, key, toolName, hash, safeArguments, invocationKey, stepId);
        } catch (EmptyResultDataAccessException conflict) {
            // invocation 键已存在或 V33 旧键冲突：走既有行读取
        } catch (org.springframework.dao.DataIntegrityViolationException legacyConflict) {
            // V33 唯一键冲突（同 worker 同参数的其他 invocation）：回退读取既有行
        }
        if (id != null) {
            return new Decision(Mode.EXECUTE, id, null, null);
        }
        return awaitExistingInvocation(runId, invocationKey, key, toolName, hash);
    }

    private Decision awaitExistingInvocation(String runId, String invocationKey, String workerKey,
                                             String toolName, String hash) {
        Decision first = readByInvocationOrLegacy(runId, invocationKey, workerKey, toolName, hash);
        for (int attempt = 0; first.mode() == Mode.PENDING_IN_FLIGHT
                && attempt < IN_FLIGHT_POLL_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(IN_FLIGHT_POLL_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            first = readByInvocationOrLegacy(runId, invocationKey, workerKey, toolName, hash);
        }
        return first;
    }

    private Decision readByInvocationOrLegacy(String runId, String invocationKey, String workerKey,
                                              String toolName, String hash) {
        return jdbcTemplate.query("""
                SELECT id, status, result FROM agent_tool_call
                WHERE run_id = ? AND (invocation_key = ? OR (worker_key = ? AND tool_name = ? AND arguments_hash = ?))
                ORDER BY (invocation_key = ?) DESC, id LIMIT 1
                """, (rs, rowNum) -> {
                    long id = rs.getLong("id");
                    String status = rs.getString("status");
                    String result = rs.getString("result");
                    if ("SUCCEEDED".equals(status)) {
                        return new Decision(Mode.REPLAY, id, result, null);
                    }
                    if ("PENDING".equals(status)) {
                        // 在途调用：不判 UNKNOWN、不改写账本，等待原执行方落定（方案 §5.2）
                        return new Decision(Mode.PENDING_IN_FLIGHT, id, null,
                                "同一调用正在另一个执行方处理中，本次未重复执行。");
                    }
                    if ("UNKNOWN".equals(status)) {
                        return new Decision(Mode.UNKNOWN, id, null,
                                "上一次工具调用结果未知，已阻止重复执行。"
                                        + "请向用户说明该操作可能已经发生，不要再次调用同一工具。");
                    }
                    return new Decision(Mode.REPLAY, id, result,
                            "该工具调用上次执行失败，已返回上次失败结果，避免重复产生副作用。");
                }, runId, invocationKey, workerKey, toolName, hash, invocationKey).stream().findFirst()
                .orElseGet(() -> new Decision(Mode.EXECUTE, null, null, null));
    }

    private Long insertPending(String runId, String workerKey, String toolName, String hash, String arguments) {
        try {
            return jdbcTemplate.queryForObject("""
                    INSERT INTO agent_tool_call (run_id, worker_key, tool_name, arguments_hash, arguments, status)
                    VALUES (?, ?, ?, ?, ?, 'PENDING')
                    ON CONFLICT (run_id, worker_key, tool_name, arguments_hash) DO NOTHING
                    RETURNING id
                    """, Long.class, runId, workerKey, toolName, hash, arguments);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    private Decision readExisting(String runId, String workerKey, String toolName, String hash) {
        return jdbcTemplate.query("""
                SELECT id, status, result FROM agent_tool_call
                WHERE run_id = ? AND worker_key = ? AND tool_name = ? AND arguments_hash = ?
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
                }, runId, workerKey, toolName, hash).stream().findFirst()
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

    /** run 的工具调用审计清单（幂等账本只读视图）：工具、参数摘要、状态与时间，供运行检查器展示。 */
    public List<Map<String, Object>> auditForRun(String runId, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT id, worker_key, tool_name, LEFT(arguments, 400) AS arguments, status,
                       LEFT(COALESCE(result, ''), 300) AS result_preview, created_at, updated_at
                FROM agent_tool_call
                WHERE run_id = ?
                ORDER BY id
                LIMIT ?
                """, runId, Math.max(1, limit));
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
        EXECUTE, REPLAY, UNKNOWN, PENDING_IN_FLIGHT
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
