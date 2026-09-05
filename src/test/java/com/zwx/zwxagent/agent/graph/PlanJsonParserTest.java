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
}
