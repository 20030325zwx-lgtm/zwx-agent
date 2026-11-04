package com.zwx.aster.eval.evaluator;

import com.zwx.aster.eval.domain.CaseOutcome;
import com.zwx.aster.eval.domain.EvalType;
import com.zwx.aster.eval.domain.RuleChecks;
import com.zwx.aster.eval.service.BackendInternalClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 检索命中评估：校验指定文档能否被该智能体的知识库检索链路召回。 */
@Component
public class RetrievalEvaluator implements Evaluator {

    private final BackendInternalClient backendClient;

    public RetrievalEvaluator(BackendInternalClient backendClient) {
        this.backendClient = backendClient;
    }

    @Override
    public EvalType type() {
        return EvalType.RETRIEVAL;
    }

    @Override
    public CaseOutcome evaluate(EvalContext context) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> payload = context.caseRow().payload();
        String question = stringOf(payload.get("question"));
        String agentKey = stringOf(payload.get("agentKey"));
        String documentId = stringOf(payload.get("documentId"));
        if (agentKey == null || documentId == null) {
            return CaseOutcome.failure("retrieval 用例缺少 agentKey/documentId",
                    System.currentTimeMillis() - startedAt);
        }
        Map<String, Object> response = backendClient.verifyRetrieval(
                context.experiment().tenantId(), agentKey, documentId, question);
        boolean matched = Boolean.TRUE.equals(response.get("matched"));
        LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("retrieval_match", matched);
        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("matchedCount", response.get("matchedCount"));
        if (response.get("topScore") != null) digest.put("topScore", response.get("topScore"));
        Object expectedPoints = payload.get("expectedPoints");
        if (expectedPoints != null) digest.put("expectedPoints", expectedPoints);
        return new CaseOutcome(RuleChecks.allPassed(checks), RuleChecks.toCheckList(checks),
                null, System.currentTimeMillis() - startedAt, null, digest, null, null, CaseOutcome.Usage.EMPTY);
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
