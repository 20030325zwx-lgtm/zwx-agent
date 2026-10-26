package com.zwx.zwxagent.agent.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentMetricsTest {

    @Test
    void countersAccumulateWithSanitizedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        metrics.runFinished("COMPLETED");
        metrics.runFinished("COMPLETED");
        metrics.runFinished("FAILED");
        metrics.runFinished(null);
        metrics.toolDenied("executeTerminalCommand", "Shell Metacharacter");
        metrics.toolConfirmationDecided("APPROVED");
        metrics.ragRecallEmpty("Super");
        metrics.ragDegraded("TIMEOUT");
        metrics.intentDecision("AUTO_KNOWLEDGE");
        metrics.quotaRejected("token-budget");
        metrics.runPausedForConfirmation();

        Assertions.assertEquals(2.0, registry.get("agent.run.finished").tag("outcome", "completed").counter().count());
        Assertions.assertEquals(1.0, registry.get("agent.run.finished").tag("outcome", "failed").counter().count());
        Assertions.assertEquals(1.0, registry.get("agent.run.paused").tag("reason", "tool-confirmation").counter().count());
        Assertions.assertEquals(1.0, registry.get("agent.tool.denied").tag("reason", "shell-metacharacter").counter().count());
        Assertions.assertEquals(1.0, registry.get("agent.tool.confirmation").tag("decision", "approved").counter().count());
        Assertions.assertEquals(1.0, registry.get("agent.rag.recall.empty").tag("agent", "super").counter().count());
        Assertions.assertEquals(1.0, registry.get("agent.rag.degraded").tag("reason", "timeout").counter().count());
        Assertions.assertEquals(1.0, registry.get("agent.intent.decision").tag("action", "auto_knowledge").counter().count());
        Assertions.assertEquals(1.0, registry.get("agent.quota.rejected").tag("kind", "token-budget").counter().count());
    }

    @Test
    void blankValuesFallBackToUnknownTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        metrics.ragRecallEmpty("");
        Assertions.assertEquals(1.0, registry.get("agent.rag.recall.empty").tag("agent", "unknown").counter().count());
    }
}
