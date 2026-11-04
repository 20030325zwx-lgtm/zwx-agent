package com.zwx.aster.eval.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 规则断言：TASK 类移植自 evaluation/eval-sets/score_task_result.py 的 9 条断言；
 * PROMPT 类为关键词/长度/正则断言。静态纯函数，便于单测。
 */
public final class RuleChecks {

    private static final Pattern FILE_PATTERN =
            Pattern.compile("[\\w\\u4e00-\\u9fa5.-]+\\.(md|pdf|txt|docx|xlsx|csv|html)", Pattern.CASE_INSENSITIVE);

    private RuleChecks() {
    }

    /** TASK 断言。expect 字段名与 task-eval-v1.json 保持一致。 */
    public static LinkedHashMap<String, Boolean> evaluateTask(Map<String, Object> expect,
                                                              String answer,
                                                              List<Map<String, Object>> plan,
                                                              boolean planProposed,
                                                              String verification,
                                                              List<String> errors,
                                                              String caseExternalId,
                                                              boolean permissionModePresent) {
        Map<String, Object> exp = expect == null ? Map.of() : expect;
        String safeAnswer = answer == null ? "" : answer;
        List<Map<String, Object>> safePlan = plan == null ? List.of() : plan;
        LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("answer_non_empty", !safeAnswer.strip().isEmpty());
        if (truthy(exp.get("casual_direct"))) {
            checks.put("no_plan", safePlan.isEmpty() && !planProposed);
        }
        if (exp.get("min_plan_steps") instanceof Number min) {
            checks.put("min_plan_steps", safePlan.size() >= min.intValue());
        }
        if (exp.get("max_plan_steps") instanceof Number max) {
            checks.put("max_plan_steps", safePlan.size() <= max.intValue());
        }
        if (truthy(exp.get("has_dependencies"))) {
            checks.put("has_dependencies", safePlan.stream()
                    .anyMatch(step -> step.get("dependsOn") instanceof List<?> deps && !deps.isEmpty()));
        }
        if (truthy(exp.get("expect_file"))) {
            String haystack = safeAnswer + EvalJson.toJson(safePlan);
            checks.put("file_mentioned", FILE_PATTERN.matcher(haystack).find());
        }
        if ("pass".equals(exp.get("verification"))) {
            checks.put("verification_pass", "pass".equals(verification));
        }
        // 显式权限模式（YOLO）下不应再出现计划确认门；casual 用例允许无 run
        if (permissionModePresent) {
            checks.put("no_unexpected_plan_gate", !planProposed
                    || (caseExternalId != null && caseExternalId.contains("casual")));
        }
        checks.put("no_errors", errors == null || errors.isEmpty());
        return checks;
    }

    /** PROMPT 断言：expect = {contains[], not_contains[], regex, min_length, max_length, judge_enabled, judge_threshold}。 */
    public static LinkedHashMap<String, Boolean> evaluatePrompt(Map<String, Object> expect,
                                                                String answer,
                                                                Map<String, Object> judge) {
        Map<String, Object> exp = expect == null ? Map.of() : expect;
        String safeAnswer = answer == null ? "" : answer;
        LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("answer_non_empty", !safeAnswer.strip().isEmpty());
        if (exp.get("contains") instanceof List<?> contains && !contains.isEmpty()) {
            String lower = safeAnswer.toLowerCase();
            checks.put("contains_all", contains.stream()
                    .allMatch(item -> lower.contains(String.valueOf(item).toLowerCase())));
        }
        if (exp.get("not_contains") instanceof List<?> notContains && !notContains.isEmpty()) {
            String lower = safeAnswer.toLowerCase();
            checks.put("not_contains_any", notContains.stream()
                    .noneMatch(item -> lower.contains(String.valueOf(item).toLowerCase())));
        }
        if (exp.get("regex") != null && !String.valueOf(exp.get("regex")).isBlank()) {
            try {
                checks.put("regex_match", Pattern.compile(String.valueOf(exp.get("regex")))
                        .matcher(safeAnswer).find());
            } catch (RuntimeException exception) {
                checks.put("regex_match", false);
            }
        }
        if (exp.get("min_length") instanceof Number min) {
            checks.put("min_length", safeAnswer.length() >= min.intValue());
        }
        if (exp.get("max_length") instanceof Number max) {
            checks.put("max_length", safeAnswer.length() <= max.intValue());
        }
        if (truthy(exp.get("judge_enabled"))) {
            double threshold = exp.get("judge_threshold") instanceof Number n ? n.doubleValue() : 0.0;
            double average = judge == null || !(judge.get("average") instanceof Number n) ? 0.0 : n.doubleValue();
            checks.put("judge_threshold", average >= threshold);
        }
        return checks;
    }

    public static boolean allPassed(LinkedHashMap<String, Boolean> checks) {
        return checks.values().stream().allMatch(Boolean::booleanValue);
    }

    public static List<Map<String, Object>> toCheckList(LinkedHashMap<String, Boolean> checks) {
        List<Map<String, Object>> list = new ArrayList<>();
        checks.forEach((name, passed) -> list.add(Map.of("name", name, "passed", passed)));
        return list;
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }
}
