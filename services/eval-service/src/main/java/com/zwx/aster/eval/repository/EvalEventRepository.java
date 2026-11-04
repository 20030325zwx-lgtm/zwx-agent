package com.zwx.aster.eval.repository;

import com.zwx.aster.eval.domain.EvalJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 实验进度事件：单调 seq；只有执行 worker 写入。 */
@Repository
public class EvalEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public EvalEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void append(UUID experimentId, String type, Map<String, Object> payload) {
        jdbcTemplate.update("""
                        INSERT INTO platform.eval_event (experiment_id, seq, type, payload)
                        SELECT ?, COALESCE(MAX(seq), 0) + 1, ?, CAST(? AS jsonb)
                        FROM platform.eval_event WHERE experiment_id = ?
                        """, experimentId, type, payload == null ? null : EvalJson.toJson(payload), experimentId);
    }

    public List<Map<String, Object>> events(UUID experimentId, int afterSeq, int limit) {
        return jdbcTemplate.queryForList("""
                        SELECT seq, type, payload::text AS payload, created_at
                        FROM platform.eval_event
                        WHERE experiment_id = ? AND seq > ?
                        ORDER BY seq
                        LIMIT ?
                        """, experimentId, afterSeq, Math.min(Math.max(limit, 1), 200));
    }
}
