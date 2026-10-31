package com.zwx.zwxagent.platform.workflow;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * C 批（Infinite Canvas 评审）：从节点重跑的纯函数——
 * 祖先闭包继承父运行输出（不重复计费）、fromNode 及下游重跑、平行分支 SKIPPED。
 */
class WorkflowRerunTest {

    private WorkflowService.GraphNodeDef node(String key, String type) {
        return new WorkflowService.GraphNodeDef(key, type, key, Map.of());
    }

    private WorkflowService.StepState step(String key, String status, String output) {
        return new WorkflowService.StepState(key, key, status, "", output,
                output == null ? null : Instant.EPOCH, output == null ? null : Instant.EPOCH);
    }

    private final WorkflowService.WorkflowGraph graph = new WorkflowService.WorkflowGraph(
            List.of(node("start", "start"), node("a", "media_image_generate"), node("b", "media_image_generate"),
                    node("c", "llm_text"), node("d", "poster_render")),
            List.of(new WorkflowService.GraphEdgeDef("start", "a"), new WorkflowService.GraphEdgeDef("start", "b"),
                    new WorkflowService.GraphEdgeDef("a", "c"), new WorkflowService.GraphEdgeDef("c", "d"),
                    new WorkflowService.GraphEdgeDef("b", "d")));

    @Test
    void rerunFromCInheritsAncestorsAndSkipsSiblingBranch() {
        List<WorkflowService.StepState> parent = List.of(
                step("start", "SUCCEEDED", "{}"),
                step("a", "SUCCEEDED", "{\"assetId\":\"asset-a\"}"),
                step("b", "SUCCEEDED", "{\"assetId\":\"asset-b\"}"),
                step("c", "FAILED", ""),
                step("d", "SKIPPED", ""));
        List<WorkflowService.StepState> steps = WorkflowService.computeRerunSteps(parent, graph, "c");
        var byKey = steps.stream().collect(java.util.stream.Collectors.toMap(
                WorkflowService.StepState::key, s -> s));
        Assertions.assertEquals("SUCCEEDED", byKey.get("a").status(), "祖先继承");
        Assertions.assertEquals("{\"assetId\":\"asset-a\"}", byKey.get("a").output(), "祖先输出原样复用");
        Assertions.assertEquals("SUCCEEDED", byKey.get("start").status());
        Assertions.assertEquals("PENDING", byKey.get("c").status(), "fromNode 重跑");
        Assertions.assertEquals("PENDING", byKey.get("d").status(), "下游重跑");
        Assertions.assertEquals("SKIPPED", byKey.get("b").status(), "平行分支跳过，不重复计费");
    }

    @Test
    void rerunFromRootRerunsEverything() {
        List<WorkflowService.StepState> parent = List.of(
                step("start", "SUCCEEDED", "{}"), step("a", "SUCCEEDED", "1"), step("b", "SUCCEEDED", "2"),
                step("c", "SUCCEEDED", "3"), step("d", "SUCCEEDED", "4"));
        List<WorkflowService.StepState> steps = WorkflowService.computeRerunSteps(parent, graph, "start");
        Assertions.assertTrue(steps.stream().allMatch(s -> "PENDING".equals(s.status())),
                "从 start 重跑 = 全部重新执行");
    }

    @Test
    void ancestorsWithNonSucceededParentFallBackToPending() {
        List<WorkflowService.StepState> parent = List.of(
                step("start", "SUCCEEDED", "{}"), step("a", "FAILED", ""),
                step("b", "SUCCEEDED", "2"), step("c", "SKIPPED", ""), step("d", "SKIPPED", ""));
        List<WorkflowService.StepState> steps = WorkflowService.computeRerunSteps(parent, graph, "d");
        var byKey = steps.stream().collect(java.util.stream.Collectors.toMap(
                WorkflowService.StepState::key, s -> s));
        Assertions.assertEquals("PENDING", byKey.get("a").status(),
                "父运行未成功的祖先不能继承，需重新执行");
        Assertions.assertEquals("PENDING", byKey.get("c").status());
    }

    @Test
    void unknownNodeRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> WorkflowService.computeRerunSteps(List.of(), graph, "ghost"));
    }
}
