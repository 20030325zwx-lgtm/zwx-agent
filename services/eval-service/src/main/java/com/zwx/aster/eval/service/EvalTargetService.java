package com.zwx.aster.eval.service;

import com.zwx.aster.eval.domain.CaseOutcome;
import com.zwx.aster.eval.domain.EvalCaseRecord;
import com.zwx.aster.eval.domain.EvalExperimentRecord;
import com.zwx.aster.eval.domain.EvalJson;
import com.zwx.aster.eval.domain.RuleChecks;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用例执行分派：INTENT/RETRIEVAL/TASK 走主站内部 API；PROMPT 直连 DashScope。
 * 任何异常转为失败结果，不打断实验。
 */
@Service
public class EvalTargetService {

    private final BackendInternalClient backendClient;
    private final DashScopeClient dashScopeClient;

    public EvalTargetService(BackendInternalClient backendClient, DashScopeClient dashScopeClient) {
        this.backendClient = backendClient;
        this.dashScopeClient = dashScopeClient;
    }

    public CaseOutcome execute(EvalExperimentRecord experiment, EvalCaseRecord caseRow) {
        long startedAt = System.currentTimeMillis();
        try {
            return switch (experiment.type()) {
                case INTENT -> runIntent(caseRow, startedAt);
                case RETRIEVAL -> runRetrieval(experiment, caseRow, startedAt);
                case PROMPT -> runPrompt(caseRow, startedAt);
                case TASK -> runTask(experiment, caseRow, startedAt);
            };
        } catch (RuntimeException exception) {
            return CaseOutcome.failure(exception.getMessage(), System.currentTimeMillis() - startedAt);
        }
    }

    // ---------------------------------------------------------------- INTENT

    private CaseOutcome runIntent(EvalCaseRecord caseRow, long startedAt) {
        String question = stringOf(caseRow.payload().get("question"));
        String expected = stringOf(caseRow.payload().get("expectedIntent"));
        Map<String, Object> response = backendClient.intent(question);
        if (!Boolean.TRUE.equals(response.get("available"))) {
            return CaseOutcome.skipped("意图路由未启用（主站返回 unavailable）");
        }
        String actual = stringOf(response.get("intent"));
        boolean matched = expected != null && expected.equals(actual);
        LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("intent_match", matched);
        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("actualIntent", actual);
        digest.put("expectedIntent", expected == null ? "" : expected);
        digest.put("confidence", response.get("confidence"));
        digest.put("complexity", response.get("complexity"));
        digest.put("router", response.get("router"));
        digest.put("routerLatencyMs", response.get("latencyMs"));
        return new CaseOutcome(RuleChecks.allPassed(checks), RuleChecks.toCheckList(checks),
                null, System.currentTimeMillis() - startedAt, null, digest, null, null);
    }

    // ---------------------------------------------------------------- RETRIEVAL

    private CaseOutcome runRetrieval(EvalExperimentRecord experiment, EvalCaseRecord caseRow, long startedAt) {
        String question = stringOf(caseRow.payload().get("question"));
        String agentKey = stringOf(caseRow.payload().get("agentKey"));
        String documentId = stringOf(caseRow.payload().get("documentId"));
        if (agentKey == null || documentId == null) {
            return CaseOutcome.failure("retrieval 用例缺少 agentKey/documentId",
                    System.currentTimeMillis() - startedAt);
        }
        Map<String, Object> response = backendClient.verifyRetrieval(
                experiment.tenantId(), agentKey, documentId, question);
        boolean matched = Boolean.TRUE.equals(response.get("matched"));
        LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("retrieval_match", matched);
        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("matchedCount", response.get("matchedCount"));
        if (response.get("topScore") != null) digest.put("topScore", response.get("topScore"));
        Object expectedPoints = caseRow.payload().get("expectedPoints");
        if (expectedPoints != null) digest.put("expectedPoints", expectedPoints);
        return new CaseOutcome(RuleChecks.allPassed(checks), RuleChecks.toCheckList(checks),
                null, System.currentTimeMillis() - startedAt, null, digest, null, null);
    }

    // ---------------------------------------------------------------- PROMPT

    private CaseOutcome runPrompt(EvalCaseRecord caseRow, long startedAt) {
        String system = stringOf(caseRow.payload().get("system"));
        String input = stringOf(caseRow.payload().get("input"));
        if (input == null || input.isBlank()) {
            return CaseOutcome.failure("prompt 用例缺少 input", System.currentTimeMillis() - startedAt);
        }
        if (!dashScopeClient.available()) {
            return CaseOutcome.skipped("评测模型不可用：DASHSCOPE_API_KEY 为空");
        }
        String answer = dashScopeClient.chat(system, input);
        Map<String, Object> expect = mapOf(caseRow.payload().get("expect"));
        Map<String, Object> judgeConfig = mapOf(caseRow.payload().get("judge"));
        Map<String, Object> judge = null;
        if (truthy(judgeConfig.get("enabled"))) {
            judge = runJudge(judgeConfig, caseRow.payload().get("reference"), answer);
        }
        Map<String, Object> expectWithJudge = new LinkedHashMap<>(expect);
        expectWithJudge.put("judge_enabled", truthy(judgeConfig.get("enabled")));
        expectWithJudge.put("judge_threshold", judgeConfig.getOrDefault("threshold", 3.5));
        LinkedHashMap<String, Boolean> checks = RuleChecks.evaluatePrompt(expectWithJudge, answer, judge);
        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("model", dashScopeClient.model());
        digest.put("modelSource", "eval-service-config");
        return new CaseOutcome(RuleChecks.allPassed(checks), RuleChecks.toCheckList(checks),
                judge, System.currentTimeMillis() - startedAt, answer, digest, null, null);
    }

    /** LLM-as-judge：四维 rubric，1-5 锚点，严格 JSON 输出。 */
    private Map<String, Object> runJudge(Map<String, Object> judgeConfig, Object reference, String answer) {
        List<String> dimensions = new ArrayList<>(List.of("相关性", "事实性", "可用性", "表达质量"));
        if (judgeConfig.get("dimensions") instanceof List<?> custom && !custom.isEmpty()) {
            dimensions = custom.stream().map(String::valueOf).toList();
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是严格的内容评审。按以下维度对「回答」各打 1-5 分整数：\n");
        prompt.append("- 相关性：1=答非所问 3=部分覆盖 5=完全紧扣意图与约束\n");
        prompt.append("- 事实性：1=明显编造 3=无依据但无硬伤 5=准确有据\n");
        prompt.append("- 可用性：1=需大改才能用 3=需修改 5=可直接使用\n");
        prompt.append("- 表达质量：1=混乱 3=通顺 5=流畅有结构\n");
        prompt.append("只输出 JSON：{\"scores\":{\"维度\":分数},\"rationale\":\"一句话理由\"}\n\n");
        if (reference != null && !String.valueOf(reference).isBlank()) {
            prompt.append("[参考答案]\n").append(reference).append("\n\n");
        }
        prompt.append("[回答]\n").append(answer);
        try {
            String raw = dashScopeClient.chat(null, prompt.toString());
            var node = EvalJson.parse(raw == null ? "{}" : raw.replaceFirst("(?s)^.*?\\{", "{"));
            Map<String, Object> result = new LinkedHashMap<>();
            Map<String, Object> scores = new LinkedHashMap<>();
            double sum = 0;
            int count = 0;
            if (node.has("scores") && node.get("scores").isObject()) {
                var scoresNode = node.get("scores");
                scoresNode.fieldNames().forEachRemaining(name ->
                        scores.put(name, scoresNode.get(name).asInt(0)));
                for (String dim : dimensions) {
                    if (scores.get(dim) instanceof Number n) {
                        sum += n.doubleValue();
                        count++;
                    }
                }
            }
            result.put("scores", scores);
            result.put("average", count == 0 ? 0.0 : sum / count);
            result.put("rationale", node.has("rationale") ? node.get("rationale").asText("") : "");
            return result;
        } catch (RuntimeException exception) {
            return Map.of("error", "judge 调用失败：" + exception.getMessage());
        }
    }

    // ---------------------------------------------------------------- TASK

    @SuppressWarnings("unchecked")
    private CaseOutcome runTask(EvalExperimentRecord experiment, EvalCaseRecord caseRow, long startedAt) {
        String task = stringOf(caseRow.payload().get("task"));
        if (task == null || task.isBlank()) {
            return CaseOutcome.failure("task 用例缺少 task 字段", System.currentTimeMillis() - startedAt);
        }
        boolean webSearch = truthy(caseRow.payload().get("web_search"));
        boolean knowledge = truthy(caseRow.payload().get("knowledge"));
        Map<String, Object> response = backendClient.taskRun(experiment.tenantId(), task,
                webSearch, knowledge, taskTimeoutMinutes);
        String answer = stringOf(response.get("answer"));
        List<Map<String, Object>> plan = response.get("plan") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        boolean planProposed = Boolean.TRUE.equals(response.get("planProposed"));
        String verification = stringOf(response.get("verification"));
        List<String> errors = response.get("errors") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        LinkedHashMap<String, Boolean> checks = RuleChecks.evaluateTask(
                expectOf(caseRow.payload()), answer, plan, planProposed, verification, errors,
                caseRow.externalId(), true);
        Map<String, Object> digest = response.get("digest") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : new LinkedHashMap<>();
        Map<String, Object> snapshot = response.get("snapshot") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : null;
        return new CaseOutcome(RuleChecks.allPassed(checks), RuleChecks.toCheckList(checks),
                null, System.currentTimeMillis() - startedAt, answer, digest, snapshot,
                stringOf(response.get("error")));
    }

    @org.springframework.beans.factory.annotation.Value("${eval.task.timeout-minutes:15}")
    private int taskTimeoutMinutes;

    // ---------------------------------------------------------------- 工具

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> expectOf(Map<String, Object> payload) {
        return payload.get("expect") instanceof Map ? (Map<String, Object>) payload.get("expect") : Map.of();
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }
}
