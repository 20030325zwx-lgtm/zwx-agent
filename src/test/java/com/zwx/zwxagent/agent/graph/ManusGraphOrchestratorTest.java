package com.zwx.zwxagent.agent.graph;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;

import java.util.concurrent.Executor;

class ManusGraphOrchestratorTest {

    private ManusGraphOrchestrator newOrchestrator() throws Exception {
        ManusGraphOrchestrator orchestrator = new ManusGraphOrchestrator(
                Mockito.mock(ChatModel.class), Runnable::run);
        orchestrator.init();
        return orchestrator;
    }

    /** 编排层退役后的终态图：START → direct → END，旧四阶段节点不得回流。 */
    @Test
    void graphCompilesWithDirectOnlyTopology() throws Exception {
        ManusGraphOrchestrator orchestrator = newOrchestrator();
        String mermaid = orchestrator.mermaidDiagram();
        Assertions.assertTrue(mermaid.contains(ManusGraphOrchestrator.NODE_DIRECT), mermaid);
        Assertions.assertFalse(mermaid.contains(ManusGraphOrchestrator.NODE_PLANNER), mermaid);
        Assertions.assertFalse(mermaid.contains(ManusGraphOrchestrator.NODE_WORKERS), mermaid);
        Assertions.assertFalse(mermaid.contains(ManusGraphOrchestrator.NODE_VERIFIER), mermaid);
        Assertions.assertFalse(mermaid.contains(ManusGraphOrchestrator.NODE_AGGREGATOR), mermaid);
    }

    @Test
    void directExecutorIsAcceptedForWorkers() throws Exception {
        Executor direct = Runnable::run;
        ManusGraphOrchestrator orchestrator = new ManusGraphOrchestrator(
                Mockito.mock(ChatModel.class), direct);
        Assertions.assertDoesNotThrow(orchestrator::init);
    }
}
