package com.zwx.aster.eval.evaluator;

import com.zwx.aster.eval.domain.CaseOutcome;
import com.zwx.aster.eval.domain.EvalJson;
import com.zwx.aster.eval.domain.EvalType;
import com.zwx.aster.eval.domain.RuleChecks;
import com.zwx.aster.eval.service.DashScopeClient;
import com.zwx.aster.eval.service.PricingProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单轮直调评估：规则断言先行，LLM-as-judge 按预算抽样。
 * 被测对象可以是「指定模型」「指定 Prompt 版本」或两者，快照里冻结。
 */
@Component
public class PromptEvaluator implements Evaluator {

    private static final List<String> DEFAULT_DIMENSIONS = List.of("相关性", "事实性", "可用性", "表达质量");
    private static final double DEFAULT_THRESHOLD = 3.5;

    private final DashScopeClient dashScopeClient;
    private final PricingProperties pricing;

    public PromptEvaluator(DashScopeClient dashScopeClient, PricingProperties pricing) {
        this.dashScopeClient = dashScopeClient;
        this.pricing = pricing;
    }

    @Override
    public EvalType type() {
        return EvalType.PROMPT;
    }

    @Override
    public CaseOutcome evaluate(EvalContext context) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> payload = context.caseRow().payload();
        String input = stringOf(payload.get("input"));
        if (input == null || input.isBlank()) {
            return CaseOutcome.failure("prompt 用例缺少 input", System.currentTimeMillis() - startedAt);
        }
        if (!dashScopeClient.available()) {
            return CaseOutcome.skipped("评测模型不可用：DASHSCOPE_API_KEY 为空");
        }
        String system = context.target().system() != null
                ? context.target().system() : stringOf(payload.get("system"));
        String model = context.target().model() != null
                ? context.target().model() : dashScopeClient.model();

        DashScopeClient.Completion answer = dashScopeClient.chat(system, input, model, context.target().temperature());
        CaseOutcome.Usage usage = pricing.usage(model, answer.inputTokens(), answer.outputTokens());

        Map<String, Object> expect = mapOf(payload.get("expect"));
        Map<String, Object> judgeConfig = mapOf(payload.get("judge"));
        boolean judgeEnabled = truthy(judgeConfig.get("enabled"));

        // 规则断言先跑：失败样本必进 judge，通过样本按预算抽样（节省成本，且可复现）
        Map<String, Object> rulesOnly = new LinkedHashMap<>(expect);
        rulesOnly.put("judge_enabled", false);
        boolean rulesPassed = RuleChecks.allPassed(RuleChecks.evaluatePrompt(rulesOnly, answer.text(), null));
        boolean runJudge = judgeEnabled && (!rulesPassed || context.budget().judgeForPassed(context.caseRow().externalId()));

        Map<String, Object> judge = null;
        if (runJudge) {
            JudgeResult judgeResult = judge(judgeConfig, payload.get("reference"), answer.text(), model);
            judge = judgeResult.judge();
            usage = usage.plus(judgeResult.usage());
        }

        Map<String, Object> expectWithJudge = new LinkedHashMap<>(expect);
        expectWithJudge.put("judge_enabled", judge != null);
        expectWithJudge.put("judge_threshold", judgeConfig.getOrDefault("threshold", DEFAULT_THRESHOLD));
        LinkedHashMap<String, Boolean> checks = RuleChecks.evaluatePrompt(expectWithJudge, answer.text(), judge);

        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("model", model);
        digest.put("promptRef", context.target().promptRef());
        digest.put("modelSource", context.target().model() == null ? "eval-service-config" : "experiment-target");
        digest.put("judgeRan", judge != null);
        if (answer.inputTokens() != null) digest.put("inputTokens", answer.inputTokens());
        if (answer.outputTokens() != null) digest.put("outputTokens", answer.outputTokens());
        return new CaseOutcome(RuleChecks.allPassed(checks), RuleChecks.toCheckList(checks),
                judge, System.currentTimeMillis() - startedAt, answer.text(), digest, null, null, usage);
    }

    /** LLM-as-judge：默认四维 1-5 锚点，维度与附加指令可由用例覆盖。 */
    private JudgeResult judge(Map<String, Object> judgeConfig, Object reference, String answer, String model) {
        List<String> dimensions = new ArrayList<>(DEFAULT_DIMENSIONS);
        if (judgeConfig.get("dimensions") instanceof List<?> custom && !custom.isEmpty()) {
            dimensions = custom.stream().map(String::valueOf).toList();
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是严格的内容评审。按以下维度对「回答」各打 1-5 分整数：\n");
        prompt.append("- 相关性：1=答非所问 3=部分覆盖 5=完全紧扣意图与约束\n");
        prompt.append("- 事实性：1=明显编造 3=无依据但无硬伤 5=准确有据\n");
        prompt.append("- 可用性：1=需大改才能用 3=需修改 5=可直接使用\n");
        prompt.append("- 表达质量：1=混乱 3=通顺 5=流畅有结构\n");
        if (judgeConfig.get("instructions") != null) {
            prompt.append(judgeConfig.get("instructions")).append('\n');
        }
        prompt.append("只输出 JSON：{\"scores\":{\"维度\":分数},\"rationale\":\"一句话理由\"}\n\n");
        if (reference != null && !String.valueOf(reference).isBlank()) {
            prompt.append("[参考答案]\n").append(reference).append("\n\n");
        }
        prompt.append("[回答]\n").append(answer);
        try {
            DashScopeClient.Completion completion = dashScopeClient.chat(null, prompt.toString(), model, 0.0);
            var node = EvalJson.parse(completion.text() == null ? "{}"
                    : completion.text().replaceFirst("(?s)^.*?\\{", "{"));
            Map<String, Object> scores = new LinkedHashMap<>();
            double sum = 0;
            int count = 0;
            if (node.has("scores") && node.get("scores").isObject()) {
                var scoresNode = node.get("scores");
                scoresNode.fieldNames().forEachRemaining(name ->
                        scores.put(name, scoresNode.get(name).asInt(0)));
                for (String dimension : dimensions) {
                    if (scores.get(dimension) instanceof Number n) {
                        sum += n.doubleValue();
                        count++;
                    }
                }
            }
            Map<String, Object> judge = new LinkedHashMap<>();
            judge.put("scores", scores);
            judge.put("average", count == 0 ? 0.0 : sum / count);
            judge.put("rationale", node.has("rationale") ? node.get("rationale").asText("") : "");
            judge.put("dimensions", dimensions);
            return new JudgeResult(judge, pricing.usage(model, completion.inputTokens(), completion.outputTokens()));
        } catch (RuntimeException exception) {
            return new JudgeResult(Map.of("error", "judge 调用失败：" + exception.getMessage()),
                    CaseOutcome.Usage.EMPTY);
        }
    }

    private record JudgeResult(Map<String, Object> judge, CaseOutcome.Usage usage) {
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }
}
