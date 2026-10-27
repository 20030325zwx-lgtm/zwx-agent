package com.zwx.zwxagent.agent.graph;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class PlanJsonParserTest {

    @Test
    void parsesFencedJsonWithRoles() {
        String output = """
                ```json
                [
                  {"title":"调研目的地","role":"researcher","detail":"检索北京三日游路线"},
                  {"title":"生成行程 PDF","role":"author","detail":"汇总为 PDF"}
                ]
                ```
                """;
        List<PlanStep> plan = PlanJsonParser.parse(output, "北京三日游");
        Assertions.assertEquals(2, plan.size());
        Assertions.assertEquals(WorkerRole.RESEARCHER, plan.get(0).role());
        Assertions.assertEquals("调研目的地", plan.get(0).title());
        Assertions.assertEquals(WorkerRole.AUTHOR, plan.get(1).role());
    }

    @Test
    void unknownRoleFallsBackToGeneral() {
        List<PlanStep> plan = PlanJsonParser.parse(
                "[{\"title\":\"x\",\"role\":\"wizard\",\"detail\":\"d\"}]", "req");
        Assertions.assertEquals(WorkerRole.GENERAL, plan.get(0).role());
    }

    @Test
    void garbageFallsBackToSingleGeneralStep() {
        List<PlanStep> plan = PlanJsonParser.parse("我觉得直接做就行了", "帮我查一下数据库里有多少用户");
        Assertions.assertEquals(1, plan.size());
        Assertions.assertEquals(WorkerRole.GENERAL, plan.get(0).role());
        Assertions.assertTrue(plan.get(0).detail().contains("查一下数据库"));
    }

    @Test
    void nullOutputFallsBack() {
        List<PlanStep> plan = PlanJsonParser.parse(null, "req");
        Assertions.assertEquals(1, plan.size());
    }

    @Test
    void moreThanMaxStepsIsTruncated() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 8; i++) {
            json.append("{\"title\":\"t").append(i).append("\",\"role\":\"general\",\"detail\":\"d\"},");
        }
        json.append("{\"title\":\"last\",\"role\":\"general\",\"detail\":\"d\"}]");
        List<PlanStep> plan = PlanJsonParser.parse(json.toString(), "req");
        Assertions.assertEquals(PlanJsonParser.MAX_STEPS, plan.size());
    }

    @Test
    void assignsPositionalIdsAndParsesDependencies() {
        String output = """
                [
                  {"id":"a1","title":"调研竞品","role":"researcher","detail":"搜索价格","acceptance":"拿到三家报价","depends_on":[]},
                  {"id":"a2","title":"写对比报告","role":"author","detail":"汇总为报告","depends_on":["a1"]}
                ]
                """;
        List<PlanStep> plan = PlanJsonParser.parse(output, "竞品调研");
        Assertions.assertEquals("s1", plan.get(0).id());
        Assertions.assertEquals("s2", plan.get(1).id());
        Assertions.assertEquals("拿到三家报价", plan.get(0).acceptance());
        Assertions.assertEquals(List.of("s1"), plan.get(1).dependsOn());
    }

    @Test
    void dropsUnknownDependenciesAndSelfReferences() {
        String output = """
                [{"id":"x","title":"t","role":"general","detail":"d","depends_on":["ghost","x","y"]}]
                """.replace("y", "");
        List<PlanStep> plan = PlanJsonParser.parse(output, "req");
        Assertions.assertEquals(List.of(), plan.get(0).dependsOn());
    }

    @Test
    void breaksDependencyCyclesByDegradingToParallel() {
        String output = """
                [
                  {"id":"s1","title":"甲","role":"general","detail":"d","depends_on":["s2"]},
                  {"id":"s2","title":"乙","role":"general","detail":"d","depends_on":["s1"]}
                ]
                """;
        List<PlanStep> plan = PlanJsonParser.parse(output, "req");
        Assertions.assertTrue(plan.get(0).dependsOn().isEmpty());
        Assertions.assertTrue(plan.get(1).dependsOn().isEmpty());
    }

    @Test
    void missingIdsStillMapDeclaredDependencies() {
        String output = """
                [
                  {"title":"调研","role":"researcher","detail":"d"},
                  {"id":"step-b","title":"写报告","role":"author","detail":"d","depends_on":["调研缺失引用"]},
                  {"title":"分析","role":"analyst","detail":"d","depends_on":["step-b"]}
                ]
                """;
        List<PlanStep> plan = PlanJsonParser.parse(output, "req");
        Assertions.assertEquals("s1", plan.get(0).id());
        Assertions.assertEquals("s3", plan.get(2).id());
        Assertions.assertEquals(List.of("s2"), plan.get(2).dependsOn());
        Assertions.assertTrue(plan.get(1).dependsOn().isEmpty());
    }
}
