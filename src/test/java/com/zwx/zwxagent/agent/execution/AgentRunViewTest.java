package com.zwx.zwxagent.agent.execution;

import com.zwx.zwxagent.agent.graph.ActivityEvent;
import com.zwx.zwxagent.agent.graph.GraphCheckpoint;
import com.zwx.zwxagent.agent.graph.PlanStep;
import com.zwx.zwxagent.agent.graph.WorkerRole;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class AgentRunViewTest {

    private AgentRun run(GraphCheckpoint checkpoint) {
        return new AgentRun("run-1", "conv-1", "tenant-1", "user-1", "super", 10L, 11L,
                "RUNNING", "整理手册", "", "", null, "{\"promptTokens\":4200}", null, checkpoint, null, 0,
                Instant.parse("2026-10-22T10:00:00Z"), Instant.parse("2026-10-22T10:01:00Z"));
    }

    @Test
    void mapsPlanEventsAndVerificationForWorkspace() {
        GraphCheckpoint checkpoint = new GraphCheckpoint(
                "workers", "verifier",
                List.of(new PlanStep("检索手册", WorkerRole.RESEARCHER, "找到迟到规则"),
                        new PlanStep("汇总规则", WorkerRole.AUTHOR, "写成结论")),
                List.of("结果一", "结果二"),
                List.of("内部活动"),
                List.of(ActivityEvent.of("work", "调研员", "开始执行（1/2）：检索手册")),
                0, "pass", "", "最终回答", "部分回答");

        AgentRunView view = AgentRunView.from(run(checkpoint));

        Assertions.assertEquals("run-1", view.id());
        Assertions.assertEquals("RUNNING", view.status());
        Assertions.assertEquals(2, view.planSize());
        Assertions.assertEquals(2, view.taskResultCount());
        Assertions.assertEquals(2, view.plan().size());
        Assertions.assertEquals("researcher", view.plan().get(0).role());
        Assertions.assertEquals("调研员", view.plan().get(0).roleLabel());
        Assertions.assertEquals("开始执行（1/2）：检索手册", view.events().getFirst().summary());
        Assertions.assertEquals("pass", view.verification().status());
        Assertions.assertTrue(view.canResume());
        Assertions.assertTrue(view.canCancel());
        Assertions.assertEquals(4, view.finalAnswerLength());
        Assertions.assertEquals(4, view.partialAnswerLength());
        Assertions.assertEquals("{\"promptTokens\":4200}", view.tokenUsage());
    }

    @Test
    void summaryAvoidsFullEventPayload() {
        GraphCheckpoint checkpoint = new GraphCheckpoint(
                "verifier", "aggregator",
                List.of(new PlanStep("检索手册", WorkerRole.RESEARCHER, "")),
                List.of("结果一"),
                List.of(), List.of(), 0, "unverified", "", "", "");
        AgentRun run = new AgentRun("run-1", "conv-1", "tenant-1", "user-1", "super", 10L, 11L,
                "INTERRUPTED", "整理手册", "", "", null, null, null, checkpoint, "boom", 2,
                Instant.parse("2026-10-22T10:00:00Z"), Instant.parse("2026-10-22T10:01:00Z"));

        AgentRunSummary summary = AgentRunSummary.from(run);

        Assertions.assertEquals(1, summary.planSize());
        Assertions.assertEquals(1, summary.taskResultCount());
        Assertions.assertEquals("unverified", summary.verificationStatus());
        Assertions.assertTrue(summary.canResume());
        Assertions.assertFalse(summary.canCancel());
        Assertions.assertEquals(2, summary.resumeCount());
    }
}
