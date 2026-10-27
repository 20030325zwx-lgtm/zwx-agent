package com.zwx.zwxagent.agent.graph;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class WorkersNodeDependencyTest {

    private static PlanStep step(String id, String... dependsOn) {
        return new PlanStep(id, "步骤" + id, WorkerRole.GENERAL, "d", List.of(dependsOn));
    }

    @Test
    void independentStepsShareSingleWave() {
        List<List<Integer>> waves = WorkersNode.dependencyWaves(List.of(
                step("s1"), step("s2"), step("s3")));
        Assertions.assertEquals(1, waves.size());
        Assertions.assertEquals(List.of(0, 1, 2), waves.get(0));
    }

    @Test
    void linearChainProducesOneWavePerLevel() {
        List<List<Integer>> waves = WorkersNode.dependencyWaves(List.of(
                step("s1"),
                step("s2", "s1"),
                step("s3", "s2")));
        Assertions.assertEquals(3, waves.size());
        Assertions.assertEquals(List.of(0), waves.get(0));
        Assertions.assertEquals(List.of(1), waves.get(1));
        Assertions.assertEquals(List.of(2), waves.get(2));
    }

    @Test
    void diamondDependenciesGroupIntoWaves() {
        List<List<Integer>> waves = WorkersNode.dependencyWaves(List.of(
                step("s1"),
                step("s2", "s1"),
                step("s3", "s1"),
                step("s4", "s2", "s3")));
        Assertions.assertEquals(3, waves.size());
        Assertions.assertEquals(List.of(1, 2), waves.get(1));
        Assertions.assertEquals(List.of(3), waves.get(2));
    }

    @Test
    void unknownOrSelfDependenciesDoNotDelay() {
        List<List<Integer>> waves = WorkersNode.dependencyWaves(List.of(
                step("s1", "ghost"),
                new PlanStep("", "t", WorkerRole.GENERAL, "d", List.of("ghost2"))));
        Assertions.assertEquals(1, waves.size());
        Assertions.assertEquals(List.of(0, 1), waves.get(0));
    }

    @Test
    void upstreamPromptCarriesReusableResultsOnly() {
        List<PlanStep> plan = List.of(step("s1"), step("s2"), step("s3", "s1", "s2"));
        List<String> results = new java.util.ArrayList<>(List.of(
                "【调研】三家报价已拿到",
                "子任务失败：超时",
                ""));
        String prompt = WorkersNode.upstreamPrompt(plan, results, List.of(0, 1, 2));
        Assertions.assertTrue(prompt.contains("【步骤s1】"));
        Assertions.assertTrue(prompt.contains("三家报价已拿到"));
        Assertions.assertFalse(prompt.contains("子任务失败"));
    }

    @Test
    void upstreamPromptEmptyWhenNoReusableResults() {
        List<PlanStep> plan = List.of(step("s1"), step("s2", "s1"));
        Assertions.assertEquals("", WorkersNode.upstreamPrompt(plan, List.of("", ""), List.of(0)));
    }
}
