package com.zwx.zwxagent.agent.execution;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词版本化：不可变版本 + 活跃指针。版本 0 表示"内置默认内容"（未保存过覆盖）。
 *
 * <p>规划/质检/汇总三个节点的提示词从这里取；run 开始时把当时的版本快照写进 agent_run.prompt_versions，
 * 事后可追溯"这次 run 用的哪版提示词"。</p>
 */
@Service
public class PromptVersionService implements PromptSource {

    public static final String PLANNER = "planner";
    public static final String VERIFIER = "verifier";
    public static final String AGGREGATOR = "aggregator";
    public static final List<String> KEYS = List.of(PLANNER, VERIFIER, AGGREGATOR);

    private final JdbcTemplate jdbcTemplate;

    public PromptVersionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ActivePrompt(String key, int version, String content, Instant updatedAt) {
    }

    public record VersionRecord(int version, String content, int baseVersion, String origin, String note, Instant createdAt) {
    }

    /** 当前生效版本；未保存过覆盖时返回 version 0 + 调用方给的内置默认文本。 */
    public ActivePrompt active(String key, String fallback) {
        List<ActivePrompt> rows = jdbcTemplate.query("""
                SELECT v.version, v.content, v.created_at
                FROM prompt_active a
                JOIN prompt_version v ON v.prompt_key = a.prompt_key AND v.version = a.version
                WHERE a.prompt_key = ?
                """, (rs, rowNum) -> new ActivePrompt(key, rs.getInt("version"), rs.getString("content"),
                instant(rs, "created_at")), key);
        if (rows.isEmpty()) return new ActivePrompt(key, 0, fallback, null);
        ActivePrompt active = rows.get(0);
        return active.content() == null ? new ActivePrompt(key, 0, fallback, active.updatedAt()) : active;
    }

    @Override
    public String resolve(String key, String fallback) {
        try {
            return active(key, fallback).content();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    /** run 开始时的版本快照：key → version（0 = 内置默认）。 */
    public Map<String, Integer> snapshot(Map<String, String> defaults) {
        Map<String, Integer> versions = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            versions.put(entry.getKey(), active(entry.getKey(), entry.getValue()).version());
        }
        return versions;
    }

    public List<VersionRecord> history(String key) {
        return jdbcTemplate.query("""
                SELECT version, content, base_version, origin, note, created_at
                FROM prompt_version WHERE prompt_key = ? ORDER BY version DESC
                """, (rs, rowNum) -> new VersionRecord(rs.getInt("version"), rs.getString("content"),
                rs.getInt("base_version"), rs.getString("origin"), rs.getString("note"),
                instant(rs, "created_at")), key);
    }

    /**
     * 保存新版本：baseVersion 必须等于当前版本，否则视为冲突（避免覆盖别人刚保存的内容）。
     * origin: panel / tool / api。
     */
    public ActivePrompt save(String key, String content, int baseVersion, String note, String origin) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("提示词内容不能为空");
        }
        Integer current = currentVersion(key);
        int expected = current == null ? 0 : current;
        if (baseVersion != expected) {
            throw new IllegalStateException("提示词已被更新（当前 v" + expected + "），请刷新后重试");
        }
        int next = expected + 1;
        jdbcTemplate.update("""
                INSERT INTO prompt_version (prompt_key, version, content, base_version, origin, note)
                VALUES (?, ?, ?, ?, ?, ?)
                """, key, next, content, baseVersion, origin == null || origin.isBlank() ? "api" : origin, note);
        jdbcTemplate.update("""
                INSERT INTO prompt_active (prompt_key, version, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (prompt_key) DO UPDATE SET version = EXCLUDED.version, updated_at = CURRENT_TIMESTAMP
                """, key, next);
        return new ActivePrompt(key, next, content, Instant.now());
    }

    private Integer currentVersion(String key) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT version FROM prompt_active WHERE prompt_key = ?", (rs, rowNum) -> rs.getInt(1), key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
