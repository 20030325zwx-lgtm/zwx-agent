package com.zwx.aster.eval.repository;

import com.zwx.aster.eval.domain.CaseOutcome;
import com.zwx.aster.eval.domain.EvalCaseRecord;
import com.zwx.aster.eval.domain.EvalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 单例结果仓储：UNIQUE(experiment_id, case_external_id) 支撑崩溃重跑/续跑的幂等 upsert。 */
@Repository
public class EvalResultRepository {

    private final JdbcTemplate jdbcTemplate;

    public EvalResultRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(UUID experimentId, String tenantId, EvalCaseRecord caseRow, CaseOutcome outcome) {
        CaseOutcome.Usage usage = outcome.usage() == null ? CaseOutcome.Usage.EMPTY : outcome.usage();
        BigDecimal cost = usage.cost();
        jdbcTemplate.update("""
                        INSERT INTO platform.eval_case_result (id, tenant_id, experiment_id, case_external_id,
                                                               passed, checks, judge, latency_ms, answer, digest,
                                                               snapshot, error, input_tokens, output_tokens,
                                                               cost_amount)
                        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, CAST(? AS jsonb),
                                CAST(? AS jsonb), ?, ?, ?, ?)
                        ON CONFLICT (experiment_id, case_external_id)
                        DO UPDATE SET passed = EXCLUDED.passed, checks = CAST(EXCLUDED.checks AS jsonb),
                                      judge = CAST(EXCLUDED.judge AS jsonb), latency_ms = EXCLUDED.latency_ms,
                                      answer = EXCLUDED.answer, digest = CAST(EXCLUDED.digest AS jsonb),
                                      snapshot = CAST(EXCLUDED.snapshot AS jsonb), error = EXCLUDED.error,
                                      input_tokens = EXCLUDED.input_tokens,
                                      output_tokens = EXCLUDED.output_tokens,
                                      cost_amount = EXCLUDED.cost_amount
                        """,
                UUID.randomUUID(), tenantId, experimentId, caseRow.externalId(),
                outcome.passed(), EvalJson.toJson(outcome.checks()),
                outcome.judge() == null ? null : EvalJson.toJson(outcome.judge()),
                outcome.latencyMs(), outcome.answer(),
                outcome.digest() == null ? null : EvalJson.toJson(outcome.digest()),
                outcome.snapshot() == null ? null : truncate(EvalJson.toJson(outcome.snapshot()), 512 * 1024),
                outcome.error(), usage.inputTokens(), usage.outputTokens(), cost);
    }

    /** 结果明细：左连用例，补齐标签与分级（数据集被删时不丢结果）。 */
    public List<Map<String, Object>> results(UUID experimentId, UUID datasetId, int versionNo, int limit) {
        return jdbcTemplate.queryForList("""
                        SELECT r.case_external_id, r.passed, r.checks::text AS checks, r.judge::text AS judge,
                               r.latency_ms, r.answer, r.digest::text AS digest, r.error, r.created_at,
                               r.input_tokens, r.output_tokens, r.cost_amount,
                               array_to_string(c.tags, ',') AS tags,
                               COALESCE(c.severity, 'normal') AS severity
                        FROM platform.eval_case_result r
                        LEFT JOIN platform.eval_case c
                               ON c.dataset_id = ? AND c.version_no = ? AND c.external_id = r.case_external_id
                        WHERE r.experiment_id = ?
                        ORDER BY r.case_external_id
                        LIMIT ?
                        """, datasetId, versionNo, experimentId, Math.min(Math.max(limit, 1), 500));
    }

    /** 已完成用例（续跑时跳过）。 */
    public List<String> completedCaseIds(UUID experimentId) {
        return jdbcTemplate.query("SELECT case_external_id FROM platform.eval_case_result WHERE experiment_id = ?",
                (rs, rowNum) -> rs.getString(1), experimentId);
    }

    /** 续跑起点：把来源实验已完成的结果复制过来，新实验只补跑缺的那部分。 */
    public int copyResultsFrom(UUID sourceExperimentId, UUID targetExperimentId, String tenantId) {
        return jdbcTemplate.update("""
                        INSERT INTO platform.eval_case_result (id, tenant_id, experiment_id, case_external_id,
                                                               passed, checks, judge, latency_ms, answer, digest,
                                                               snapshot, error, input_tokens, output_tokens,
                                                               cost_amount)
                        SELECT gen_random_uuid(), ?, ?, case_external_id, passed, checks, judge, latency_ms,
                               answer, digest, snapshot, error, input_tokens, output_tokens, cost_amount
                        FROM platform.eval_case_result WHERE experiment_id = ?
                        """, tenantId, targetExperimentId, sourceExperimentId);
    }

    private static String truncate(String json, int maxChars) {
        if (json == null || json.length() <= maxChars) return json;
        return EvalJson.toJson(Map.of(
                "truncated", true,
                "originalChars", json.length(),
                "note", "轨迹快照超过上限被截断，完整轨迹按 digest.run_id 查询轨迹 API"));
    }
}
