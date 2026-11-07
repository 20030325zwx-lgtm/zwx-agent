package com.zwx.zwxagent.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class GraphCheckpointJsonTest {

    private static GraphCheckpoint sample() {
        return new GraphCheckpoint(
                "workers", "verifier",
                List.of(new PlanStep("s1", "查询", WorkerRole.GENERAL, "查资料", List.of(), "有结论"),
                        new PlanStep("s2", "整理", WorkerRole.AUTHOR, "整理产出", List.of("s1"), "文件生成")),
                List.of("【查询】结论 A", "【整理】产出 B"),
                List.of("第 1 步完成", "工具 web 执行完成"),
                List.of(ActivityEvent.of("work", "通用助理", "开始执行（1/2）：查询", "worker-1-general"),
                        ActivityEvent.of("verify", "质检员", "质检完成")),
                2, "pass", "", "最终草稿", "部分答案草稿", List.of(1));
    }

    @Test
    void writeReadRoundTripPreservesAllFields() {
        ObjectMapper mapper = new ObjectMapper();
        GraphCheckpoint original = sample();
        Map<String, Object> state = GraphCheckpointJson.write(original);
        String json = new ObjectMapper().valueToTree(state).toString();
        GraphCheckpoint restored = GraphCheckpointJson.read(mapper, json);

        Assertions.assertEquals(original.lastNode(), restored.lastNode());
        Assertions.assertEquals(original.nextNode(), restored.nextNode());
        Assertions.assertEquals(original.plan(), restored.plan());
        Assertions.assertEquals(original.taskResults(), restored.taskResults());
        Assertions.assertEquals(original.activities(), restored.activities());
        Assertions.assertEquals(original.events(), restored.events());
        Assertions.assertEquals(original.revision(), restored.revision());
        Assertions.assertEquals(original.verificationStatus(), restored.verificationStatus());
        Assertions.assertEquals(original.finalAnswer(), restored.finalAnswer());
        Assertions.assertEquals(original.partialAnswer(), restored.partialAnswer());
        Assertions.assertEquals(original.reviseSteps(), restored.reviseSteps());
    }

    @Test
    void readToleratesMissingFieldsAndBlankInput() {
        GraphCheckpoint restored = GraphCheckpointJson.read(new ObjectMapper(), "{}");
        Assertions.assertEquals("", restored.lastNode());
        Assertions.assertTrue(restored.plan().isEmpty());
        Assertions.assertEquals(0, restored.revision());

        Assertions.assertTrue(GraphCheckpointJson.read(new ObjectMapper(), "").plan().isEmpty());
    }

    @Test
    void summaryCarriesCompactDigest() {
        Map<String, Object> summary = GraphCheckpointJson.summary(sample());
        Assertions.assertEquals("workers", summary.get("lastNode"));
        Assertions.assertEquals(2, summary.get("planSize"));
        Assertions.assertEquals(2, summary.get("resultSize"));
    }
}
