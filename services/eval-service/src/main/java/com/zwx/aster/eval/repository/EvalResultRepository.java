package com.zwx.aster.eval.repository;

import com.zwx.aster.eval.domain.CaseOutcome;
import com.zwx.aster.eval.domain.EvalCaseRecord;
import com.zwx.aster.eval.domain.EvalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 单例结果仓储：UNIQUE(experiment_id, case_external_id) 支撑崩溃重跑的幂等 upsert。 */
@Repository
public class EvalResultRepository {

    private final JdbcTemplate jdbcTemplate;

    public EvalResultRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(UUID experimentId, String tenantId, EvalCaseRecord caseRow, CaseOutcome outcome) {
        jdbcTemplate.update("""
                        INSERT INTO platform.eval_case_result (id, tenant_id, experiment_id, case_external_id,
                                                               passed, checks, judge, latency_ms, answer, digest,
                                                               snapshot, error)
                        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, CAST(? AS jsonb),
                                CAST(? AS jsonb), ?)
                        ON CONFLICT (experiment_id, case_external_id)
                        DO UPDATE SET passed = EXCLUDED.passed, checks = CAST(EXCLUDED.checks AS jsonb),
                                      judge = CAST(EXCLUDED.judge AS jsonb), latency_ms = EXCLUDED.latency_ms,
                                      answer = EXCLUDED.answer, digest = CAST(EXCLUDED.digest AS jsonb),
                                      snapshot = CAST(EXCLUDED.snapshot AS jsonb), error = EXCLUDED.error
                        """,
                UUID.randomUUID(), tenantId, experimentId, caseRow.externalId(),
                outcome.passed(), EvalJson.toJson(outcome.checks()),
                outcome.judge() == null ? null : EvalJson.toJson(outcome.judge()),
                outcome.latencyMs(), outcome.answer(),
                outcome.digest() == null ? null : EvalJson.toJson(outcome.digest()),
                outcome.snapshot() == null ? null : truncate(EvalJson.toJson(outcome.snapshot()), 512 * 1024),
                outcome.error());
    }

    public List<Map<String, Object>> results(UUID experimentId, int limit) {
        return jdbcTemplate.queryForList("""
                        SELECT case_external_id, passed, checks::text AS checks, judge::text AS judge,
                               latency_ms, answer, digest::text AS digest, error, created_at
                        FROM platform.eval_case_result
                        WHERE experiment_id = ?
                        ORDER BY case_external_id
                        LIMIT ?
                        """, experimentId, Math.min(Math.max(limit, 1), 500));
    }

    private static String truncate(String json, int maxChars) {
        if (json == null || json.length() <= maxChars) return json;
        return EvalJson.toJson(Map.of(
                "truncated", true,
                "originalChars", json.length(),
                "note", "轨迹快照超过上限被截断，完整轨迹按 digest.run_id 查询轨迹 API"));
    }
}
