package com.zwx.aster.eval.service;

import com.zwx.aster.eval.domain.EvalExperimentRecord;
import com.zwx.aster.eval.domain.EvalJson;
import com.zwx.aster.eval.repository.EvalResultRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报告层：按维度聚合、趋势、门禁判定、导出。
 * 全部从已落库的结果读取，不重跑任何模型调用。
 */
@Service
public class EvalReportService {

    private final EvalResultRepository resultRepository;
    private final JdbcTemplate jdbcTemplate;

    public EvalReportService(EvalResultRepository resultRepository, JdbcTemplate jdbcTemplate) {
        this.resultRepository = resultRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> report(EvalExperimentRecord experiment, String groupBy) {
        List<Map<String, Object>> rows = rowsOf(experiment);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overall", EvalRunner.totals(rows));
        result.put("groupBy", groupBy);
        result.put("groups", switch (groupBy == null ? "tag" : groupBy) {
            case "check" -> groupByCheck(rows);
            case "dimension" -> groupByDimension(rows);
            default -> groupByTag(rows);
        });
        return result;
    }

    /** 同一数据集的历次实验，用于看模型/提示词漂移。 */
    public Map<String, Object> trend(String tenantId, java.util.UUID datasetId, int limit) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT id::text, created_at, status, eval_type, totals::text AS totals,
                               candidate::text AS candidate, git_commit
                        FROM platform.eval_experiment
                        WHERE dataset_id = ? AND tenant_id = ? AND status IN ('succeeded', 'failed')
                        ORDER BY created_at DESC
                        LIMIT ?
                        """, datasetId, tenantId, Math.min(Math.max(limit, 1), 100));
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> totals = EvalJson.parseMap(String.valueOf(row.get("totals")));
            Map<String, Object> candidate = EvalJson.parseMap(String.valueOf(row.get("candidate")));
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", row.get("id"));
            point.put("createdAt", row.get("created_at"));
            point.put("status", row.get("status"));
            point.put("evalType", row.get("eval_type"));
            point.put("gitCommit", row.get("git_commit"));
            point.put("target", com.zwx.aster.eval.domain.TargetView.from(candidate).describe());
            point.put("passRate", totals.get("passRate"));
            point.put("judgeAvg", totals.get("judgeAvg"));
            point.put("totalCost", totals.get("totalCost"));
            point.put("avgLatencyMs", totals.get("avgLatencyMs"));
            point.put("total", totals.get("total"));
            points.add(point);
        }
        return Map.of("datasetId", datasetId.toString(), "points", points);
    }

    /** 门禁：给 CI/发布脚本消费，只返回判定与理由，不做副作用。 */
    public Map<String, Object> gate(EvalExperimentRecord experiment, Double minPassRate,
                                    Integer maxCriticalFailed, BigDecimal maxAvgCost) {
        Map<String, Object> totals = EvalRunner.totals(rowsOf(experiment));
        List<String> reasons = new ArrayList<>();
        if (!"succeeded".equals(experiment.status())) {
            reasons.add("实验状态为 " + experiment.status() + "，不是 succeeded");
        }
        Object passRate = totals.get("passRate");
        if (minPassRate != null && (passRate == null || ((Number) passRate).doubleValue() < minPassRate)) {
            reasons.add("通过率 " + passRate + " 低于阈值 " + minPassRate);
        }
        Object criticalFailed = totals.get("criticalFailed");
        if (maxCriticalFailed != null && criticalFailed instanceof Number n && n.intValue() > maxCriticalFailed) {
            reasons.add("critical 用例未过 " + n.intValue() + " 条，超过上限 " + maxCriticalFailed);
        }
        Object avgCost = totals.get("avgCostPerCase");
        if (maxAvgCost != null && avgCost instanceof Number n && BigDecimal.valueOf(n.doubleValue()).compareTo(maxAvgCost) > 0) {
            reasons.add("单例成本 " + n + " 元高于上限 " + maxAvgCost);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pass", reasons.isEmpty());
        result.put("reasons", reasons);
        result.put("totals", totals);
        return result;
    }

    /** CSV 导出：给人看结果、给脚本做二次分析。 */
    public String exportCsv(EvalExperimentRecord experiment) {
        List<Map<String, Object>> rows = rowsOf(experiment);
        StringBuilder csv = new StringBuilder();
        csv.append("external_id,passed,severity,tags,latency_ms,input_tokens,output_tokens,cost_amount,judge_avg,error\n");
        for (Map<String, Object> row : rows) {
            Map<String, Object> judge = EvalJson.parseMap(String.valueOf(row.get("judge")));
            csv.append(csvCell(row.get("case_external_id"))).append(',')
                    .append(row.get("passed") == null ? "skipped" : row.get("passed")).append(',')
                    .append(csvCell(row.get("severity"))).append(',')
                    .append(csvCell(row.get("tags"))).append(',')
                    .append(nullSafe(row.get("latency_ms"))).append(',')
                    .append(nullSafe(row.get("input_tokens"))).append(',')
                    .append(nullSafe(row.get("output_tokens"))).append(',')
                    .append(nullSafe(row.get("cost_amount"))).append(',')
                    .append(nullSafe(judge.get("average"))).append(',')
                    .append(csvCell(row.get("error"))).append('\n');
        }
        return csv.toString();
    }

    // ---------------------------------------------------------------- 聚合

    private List<Map<String, Object>> rowsOf(EvalExperimentRecord experiment) {
        return resultRepository.results(experiment.id(), experiment.datasetId(),
                experiment.datasetVersionNo(), 500);
    }

    private static List<Map<String, Object>> groupByTag(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String tags = row.get("tags") == null ? null : String.valueOf(row.get("tags"));
            List<String> keys = tags == null || tags.isBlank() ? List.of("未打标")
                    : java.util.Arrays.stream(tags.split(",")).map(String::trim).filter(tag -> !tag.isEmpty()).toList();
            for (String key : keys.isEmpty() ? List.of("未打标") : keys) {
                buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
        }
        return buckets.entrySet().stream()
                .map(entry -> summarize(entry.getKey(), entry.getValue()))
                .sorted((left, right) -> Integer.compare(
                        ((Number) right.get("total")).intValue(), ((Number) left.get("total")).intValue()))
                .toList();
    }

    private static List<Map<String, Object>> groupByCheck(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            List<Map<String, Object>> checks = parseChecks(row.get("checks"));
            for (Map<String, Object> check : checks) {
                String name = String.valueOf(check.getOrDefault("name", "unknown"));
                buckets.computeIfAbsent(name, ignored -> new ArrayList<>()).add(row);
            }
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (var entry : buckets.entrySet()) {
            List<Map<String, Object>> bucketRows = entry.getValue();
            int failed = 0;
            for (Map<String, Object> row : bucketRows) {
                for (Map<String, Object> check : parseChecks(row.get("checks"))) {
                    if (entry.getKey().equals(String.valueOf(check.get("name")))
                            && !Boolean.TRUE.equals(check.get("passed"))) {
                        failed++;
                        break;
                    }
                }
            }
            Map<String, Object> summary = summarize(entry.getKey(), bucketRows);
            summary.put("failedChecks", failed);
            groups.add(summary);
        }
        groups.sort((left, right) -> Integer.compare(
                ((Number) right.get("failedChecks")).intValue(), ((Number) left.get("failedChecks")).intValue()));
        return groups;
    }

    private static List<Map<String, Object>> groupByDimension(List<Map<String, Object>> rows) {
        Map<String, List<Double>> scores = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> judge = EvalJson.parseMap(String.valueOf(row.get("judge")));
            if (judge.get("scores") instanceof Map<?, ?> scoreMap) {
                scoreMap.forEach((dimension, value) -> {
                    if (value instanceof Number n) {
                        scores.computeIfAbsent(String.valueOf(dimension), ignored -> new ArrayList<>())
                                .add(n.doubleValue());
                    }
                });
            }
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        scores.forEach((dimension, values) -> {
            double sum = values.stream().mapToDouble(Double::doubleValue).sum();
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("key", dimension);
            group.put("samples", values.size());
            group.put("average", Math.round(100.0 * sum / values.size()) / 100.0);
            groups.add(group);
        });
        groups.sort((left, right) -> Double.compare(
                ((Number) left.get("average")).doubleValue(), ((Number) right.get("average")).doubleValue()));
        return groups;
    }

    private static Map<String, Object> summarize(String key, List<Map<String, Object>> rows) {
        Map<String, Object> totals = EvalRunner.totals(rows);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("key", key);
        summary.put("total", totals.get("total"));
        summary.put("passed", totals.get("passed"));
        summary.put("failed", totals.get("failed"));
        summary.put("passRate", totals.get("passRate"));
        summary.put("avgLatencyMs", totals.get("avgLatencyMs"));
        summary.put("judgeAvg", totals.get("judgeAvg"));
        Object cost = totals.get("totalCost");
        summary.put("totalCost", cost instanceof Number n
                ? BigDecimal.valueOf(n.doubleValue()).setScale(6, RoundingMode.HALF_UP) : null);
        return summary;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseChecks(Object value) {
        if (value == null) return List.of();
        try {
            return EvalJson.parseList(String.valueOf(value));
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private static String csvCell(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value).replace("\"", "\"\"");
        return text.contains(",") || text.contains("\"") || text.contains("\n") ? "\"" + text + "\"" : text;
    }

    private static Object nullSafe(Object value) {
        return value == null ? "" : value;
    }
}
