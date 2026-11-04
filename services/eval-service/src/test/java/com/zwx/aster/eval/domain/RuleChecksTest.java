package com.zwx.aster.eval.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 对齐 score_task_result.py 的 9 条断言 + PROMPT 断言 + 四类 schema 规范化。 */
class RuleChecksTest {

    private static Map<String, Object> planStep(Object... dependsOn) {
        return Map.of("id", "s1", "title", "t", "dependsOn", List.of(dependsOn));
    }

    @Test
    void casualDirectRequiresNoPlan() {
        assertTrue(RuleChecks.evaluateTask(Map.of("casual_direct", true), "回答", List.of(),
                false, null, List.of(), "task-003-casual-direct", false).get("no_plan"));
        assertFalse(RuleChecks.evaluateTask(Map.of("casual_direct", true), "回答",
                List.of(planStep()), false, null, List.of(), "case-x", false).get("no_plan"));
    }

    @Test
    void planStepsAndDependencies() {
        var plan = List.of(planStep(), planStep("s1"));
        Map<String, Object> minTwo = Map.of("min_plan_steps", 2, "has_dependencies", true);
        Map<String, Object> minThree = Map.of("min_plan_steps", 3);
        assertTrue(RuleChecks.evaluateTask(minTwo, "a", plan, false, null, List.of(), "c", false)
                .get("min_plan_steps"));
        assertFalse(RuleChecks.evaluateTask(minThree, "a", plan, false, null, List.of(), "c", false)
                .get("min_plan_steps"));
        assertTrue(RuleChecks.evaluateTask(Map.<String, Object>of("has_dependencies", true), "a", plan,
                false, null, List.of(), "c", false).get("has_dependencies"));
    }

    @Test
    void fileAndVerificationChecks() {
        assertTrue(RuleChecks.evaluateTask(Map.of("expect_file", true), "报告已保存为 report.md",
                List.of(), false, null, List.of(), "c", false).get("file_mentioned"));
        assertFalse(RuleChecks.evaluateTask(Map.of("verification", "pass"), "a", List.of(), false,
                "revise", List.of(), "c", false).get("verification_pass"));
    }

    @Test
    void planGateAndErrors() {
        assertFalse(RuleChecks.evaluateTask(Map.of(), "a", List.of(), true, null, List.of(),
                "task-001", true).get("no_unexpected_plan_gate"));
        assertTrue(RuleChecks.evaluateTask(Map.of(), "a", List.of(), true, null, List.of(),
                "task-003-casual-direct", true).get("no_unexpected_plan_gate"));
        assertFalse(RuleChecks.evaluateTask(Map.of(), "a", List.of(), false, null,
                List.of("boom"), "c", false).get("no_errors"));
    }

    @Test
    void promptChecksCoverRulesAndJudge() {
        var expect = new LinkedHashMap<String, Object>();
        expect.put("contains", List.of("年假", "5"));
        expect.put("not_contains", List.of("抱歉"));
        expect.put("min_length", 4);
        expect.put("judge_enabled", true);
        expect.put("judge_threshold", 3.5);
        var good = RuleChecks.evaluatePrompt(expect, "员工年假为 5 天起。", Map.of("average", 4.0));
        assertTrue(RuleChecks.allPassed(good));
        var bad = RuleChecks.evaluatePrompt(expect, "抱歉，不知道。", Map.of("average", 2.0));
        assertFalse(bad.get("contains_all"));
        assertFalse(bad.get("judge_threshold"));
    }

    @Test
    void normalizesIntentRetrievalTaskPrompt() {
        assertEquals(EvalType.INTENT, EvalCaseNormalizer.normalize(Map.of(
                "id", "i1", "question", "你好", "expectedIntent", "闲聊问答"), 0).evalType());
        var retrieval = EvalCaseNormalizer.normalize(Map.of(
                "id", "r1", "question", "q", "agentKey", "test",
                "evidence", Map.of("documentId", "d1", "filename", "f.md")), 0);
        assertEquals(EvalType.RETRIEVAL, retrieval.evalType());
        assertEquals("d1", retrieval.payload().get("documentId"));
        var task = EvalCaseNormalizer.normalize(Map.of(
                "id", "t1", "task", "查年假", "knowledge", true), 0);
        assertEquals(EvalType.TASK, task.evalType());
        assertEquals(true, task.payload().get("knowledge"));
        var prompt = EvalCaseNormalizer.normalize(Map.of(
                "system", "s", "input", "i"), 2);
        assertEquals(EvalType.PROMPT, prompt.evalType());
        assertEquals("case-003", prompt.externalId());
        assertThrows(IllegalArgumentException.class,
                () -> EvalCaseNormalizer.normalize(Map.of("id", "x"), 0));
    }
}
