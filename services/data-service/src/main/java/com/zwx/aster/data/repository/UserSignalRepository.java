package com.zwx.aster.data.repository;

import com.zwx.aster.data.domain.DataJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 隐式信号仓储：append-only，写入不阻塞主链路。 */
@Repository
public class UserSignalRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserSignalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(String tenantId, String userId, String event, String subjectType,
                       String subjectId, Map<String, Object> props) {
        jdbcTemplate.update("""
                        INSERT INTO platform.user_signal (tenant_id, user_id, event, subject_type, subject_id, props)
                        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                        """, tenantId, userId, event, subjectType, subjectId,
                DataJson.toJson(props == null ? Map.of() : props));
    }

    /** 按事件聚合：采纳率/重生成率等指标的分子分母都从这里来。 */
    public Map<String, Object> summary(String tenantId, Instant since) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT event, COUNT(*) AS total, COUNT(DISTINCT subject_id) AS subjects
                        FROM platform.user_signal
                        WHERE tenant_id = ? AND created_at >= ?
                        GROUP BY event ORDER BY total DESC
                        """, tenantId, java.sql.Timestamp.from(since));
        long total = 0;
        Map<String, Object> byEvent = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long count = ((Number) row.get("total")).longValue();
            total += count;
            byEvent.put(String.valueOf(row.get("event")), Map.of(
                    "total", count,
                    "subjects", ((Number) row.get("subjects")).longValue()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("since", since.toString());
        result.put("total", total);
        result.put("byEvent", byEvent);
        return result;
    }

    public List<Map<String, Object>> recent(String tenantId, int limit) {
        return jdbcTemplate.queryForList("""
                        SELECT event, subject_type, subject_id, props::text AS props, created_at
                        FROM platform.user_signal WHERE tenant_id = ?
                        ORDER BY created_at DESC LIMIT ?
                        """, tenantId, Math.min(Math.max(limit, 1), 200));
    }
}
