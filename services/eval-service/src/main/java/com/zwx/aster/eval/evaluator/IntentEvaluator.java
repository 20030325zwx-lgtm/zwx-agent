package com.zwx.aster.eval.evaluator;

import com.zwx.aster.eval.domain.CaseOutcome;
import com.zwx.aster.eval.domain.EvalType;
import com.zwx.aster.eval.domain.RuleChecks;
import com.zwx.aster.eval.service.BackendInternalClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 意图路由评估：判路由结果是否与期望一致。
 * 「该走任务却闲聊直达」的代价远大于反向，所以支持 confidenceMin 与 critical 用例分级。
 */
@Component
public class IntentEvaluator implements Evaluator {

    private final BackendInternalClient backendClient;

    public IntentEvaluator(BackendInternalClient backendClient) {
        this.backendClient = backendClient;
    }

    @Override
    public EvalType type() {
        return EvalType.INTENT;
    }

    @Override
    public CaseOutcome evaluate(EvalContext context) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> payload = context.caseRow().payload();
        String question = stringOf(payload.get("question"));
        String expected = stringOf(payload.get("expectedIntent"));
        Map<String, Object> response = backendClient.intent(question);
        if (!Boolean.TRUE.equals(response.get("available"))) {
            return CaseOutcome.skipped("意图路由未启用（主站返回 unavailable）");
        }
        String actual = stringOf(response.get("intent"));
        boolean matched = expected != null && expected.equals(actual);

        LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("intent_match", matched);
        Object confidenceMin = expectOf(payload).get("confidenceMin");
        if (confidenceMin instanceof Number min && response.get("confidence") instanceof Number confidence) {
            checks.put("confidence_min", confidence.doubleValue() >= min.doubleValue());
        }

        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("actualIntent", actual);
        digest.put("expectedIntent", expected == null ? "" : expected);
        digest.put("confidence", response.get("confidence"));
        digest.put("complexity", response.get("complexity"));
        digest.put("router", response.get("router"));
        digest.put("routerLatencyMs", response.get("latencyMs"));
        return new CaseOutcome(RuleChecks.allPassed(checks), RuleChecks.toCheckList(checks),
                null, System.currentTimeMillis() - startedAt, null, digest, null, null, CaseOutcome.Usage.EMPTY);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> expectOf(Map<String, Object> payload) {
        return payload.get("expect") instanceof Map ? (Map<String, Object>) payload.get("expect") : Map.of();
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
