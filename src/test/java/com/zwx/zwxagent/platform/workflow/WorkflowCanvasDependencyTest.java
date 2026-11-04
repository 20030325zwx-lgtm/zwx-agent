package com.zwx.zwxagent.platform.workflow;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowCanvasDependencyTest {
    private final WorkflowService.WorkflowGraph graph = new WorkflowService.WorkflowGraph(
            List.of(new WorkflowService.GraphNodeDef("a", "canvas_input", "a", Map.of()),
                    new WorkflowService.GraphNodeDef("b", "canvas_input", "b", Map.of()),
                    new WorkflowService.GraphNodeDef("join", "canvas_input", "join", Map.of())),
            List.of(new WorkflowService.GraphEdgeDef("a", "join"), new WorkflowService.GraphEdgeDef("b", "join")));

    private WorkflowService.StepState step(String id, String status) {
        return new WorkflowService.StepState(id, id, status, "", "", null, null);
    }

    @Test
    void joinWaitsForEveryParentAndNeverTreatsTerminalFailureAsCompletion() {
        for (String status : List.of("PENDING", "QUEUED", "RUNNING", "WAITING_EXTERNAL", "WAITING_SELECTION", "FAILED", "CANCELLED", "SKIPPED")) {
            assertFalse(WorkflowService.allParentsSucceeded(graph, "join", List.of(step("a", "SUCCEEDED"), step("b", status))), status);
        }
        assertFalse(WorkflowService.allParentsSucceeded(graph, "join", List.of(step("a", "SUCCEEDED"))));
        assertTrue(WorkflowService.allParentsSucceeded(graph, "join", List.of(step("a", "SUCCEEDED"), step("b", "SUCCEEDED"))));
        assertTrue(WorkflowService.allParentsSucceeded(graph, "a", List.of()));
    }
}
