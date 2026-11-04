package com.zwx.aster.eval.evaluator;

import com.zwx.aster.eval.domain.CaseOutcome;
import com.zwx.aster.eval.domain.EvalType;
import com.zwx.aster.eval.domain.RuleChecks;
import com.zwx.aster.eval.service.BackendInternalClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全链路任务评估：经主站内部 API 以隔离评测账号 + YOLO 跑一次完整任务。
 * token 有真实值，成本留空——TASK 的模型由智能体配置决定，eval-service 不知道单价。
 */
@Component
public class TaskEvaluator implements Evaluator {

    private final BackendInternalClient backendClient;

    @Value("${eval.task.timeout-minutes:15}")
    private int timeoutMinutes;

    public TaskEvaluator(BackendInternalClient backendClient) {
        this.backendClient = backendClient;
    }

    @Override
    public EvalType type() {
        return EvalType.TASK;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CaseOutcome evaluate(EvalContext context) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> payload = context.caseRow().payload();
        String task = stringOf(payload.get("task"));
        if (task == null || task.isBlank()) {
            return CaseOutcome.failure("task 用例缺少 task 字段", System.currentTimeMillis() - startedAt);
        }
        boolean webSearch = truthy(payload.get("web_search"));
        boolean knowledge = truthy(payload.get("knowledge"));
        Map<String, Object> response = backendClient.taskRun(context.experiment().tenantId(), task,
                webSearch, knowledge, timeoutMinutes);

        String answer = stringOf(response.get("answer"));
        List<Map<String, Object>> plan = response.get("plan") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        boolean planProposed = Boolean.TRUE.equals(response.get("planProposed"));
        String verification = stringOf(response.get("verification"));
        List<String> errors = response.get("errors") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        LinkedHashMap<String, Boolean> checks = RuleChecks.evaluateTask(
                expectOf(payload), answer, plan, planProposed, verification, errors,
                context.caseRow().externalId(), true);
        Map<String, Object> digest = response.get("digest") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        Map<String, Object> snapshot = response.get("snapshot") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : null;
        CaseOutcome.Usage usage = CaseOutcome.Usage.of(
                intOf(digest.get("inputTokens")), intOf(digest.get("outputTokens")), null);
        return new CaseOutcome(RuleChecks.allPassed(checks), RuleChecks.toCheckList(checks),
                null, System.currentTimeMillis() - startedAt, answer, digest, snapshot,
                stringOf(response.get("error")), usage);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> expectOf(Map<String, Object> payload) {
        return payload.get("expect") instanceof Map ? (Map<String, Object>) payload.get("expect") : Map.of();
    }

    private static Integer intOf(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }
}
