package com.zwx.zwxagent.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class VerifierNodeEvidenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PlanStep step(String title, WorkerRole role, String detail, String acceptance) {
        return new PlanStep("s1", title, role, detail, List.of(), acceptance);
    }

    @Test
    void normalResultProducesNoGap() {
        List<PlanStep> plan = List.of(step("调研", WorkerRole.RESEARCHER, "d", ""));
        Assertions.assertTrue(VerifierNode.evidenceGaps(plan, List.of("【调研】完成，拿到三家报价")).isEmpty());
    }

    @Test
    void failedStepIsNamedAsGapTarget() {
        List<PlanStep> plan = List.of(
                step("调研", WorkerRole.RESEARCHER, "d", ""),
                step("汇总", WorkerRole.GENERAL, "d", ""));
        List<String> results = List.of("子任务失败：超时", "【汇总】已取消");
        Assertions.assertFalse(VerifierNode.evidenceGaps(plan, results).isEmpty());
        Assertions.assertEquals(List.of(0, 1), VerifierNode.gapSteps(plan, results));
    }

    @Test
    void authorStepWithoutFileMentionIsGap() {
        List<PlanStep> plan = List.of(step("写报告", WorkerRole.AUTHOR, "输出调研报告", ""));
        List<String> results = List.of("【写报告】报告已经写完，结论如下……");
        List<String> gaps = VerifierNode.evidenceGaps(plan, results);
        Assertions.assertFalse(gaps.isEmpty());
        Assertions.assertTrue(gaps.get(0).contains("未包含任何文件名"));
        Assertions.assertEquals(List.of(0), VerifierNode.gapSteps(plan, results));
    }

    @Test
    void authorStepWithFileNamePassesGate() {
        List<PlanStep> plan = List.of(step("写报告", WorkerRole.AUTHOR, "输出调研报告", "产出 md 文件"));
        List<String> results = List.of("【写报告】已保存竞品对比报告.pdf 至工作区");
        Assertions.assertTrue(VerifierNode.evidenceGaps(plan, results).isEmpty());
        Assertions.assertTrue(VerifierNode.gapSteps(plan, results).isEmpty());
    }

    @Test
    void generalStepWithoutFilePromiseIsNotGap() {
        List<PlanStep> plan = List.of(step("回答问题", WorkerRole.GENERAL, "依据资料作答", ""));
        Assertions.assertTrue(VerifierNode.evidenceGaps(plan, List.of("【回答问题】年假为 5 天")).isEmpty());
    }

    @Test
    void parseStepTargetsClampsAndDeduplicates() throws Exception {
        Assertions.assertEquals(List.of(0, 2), VerifierNode.parseStepTargets(
                MAPPER.readTree("[0,2,2,9,-1,\"x\"]"), 3));
        Assertions.assertEquals(List.of(), VerifierNode.parseStepTargets(
                MAPPER.readTree("[]"), 3));
        Assertions.assertEquals(List.of(), VerifierNode.parseStepTargets(
                MAPPER.readTree("null"), 3));
    }

    @Test
    void systemPromptMentionsStepTargetsAndAcceptance() {
        Assertions.assertTrue(VerifierNode.SYSTEM_PROMPT.contains("steps"));
        Assertions.assertTrue(VerifierNode.SYSTEM_PROMPT.contains("完成判据"));
    }
}
