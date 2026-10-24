package com.zwx.evaluation.flowable;

import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DocumentWorkflowTest {
    @Autowired RuntimeService runtimeService;
    @Autowired TaskService taskService;
    @Autowired HistoryService historyService;

    @Test
    void runsAutomatedStepsPausesForHumanAndCompletesOnApproval() {
        var process = runtimeService.startProcessInstanceByKey("document-indexing", Map.of(
                "documentId", "doc-123", "tenantId", "tenant-a"));

        var reviewTask = taskService.createTaskQuery().processInstanceId(process.getId()).singleResult();
        assertThat(reviewTask).isNotNull();
        assertThat(reviewTask.getName()).isEqualTo("Human approval");
        assertThat(runtimeService.getVariable(process.getId(), "lastCompletedStep")).isEqualTo("verify");

        taskService.complete(reviewTask.getId(), Map.of("approved", true, "reviewNote", "retrieval checked"));

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(process.getId()).singleResult()).isNull();
        assertThat(historyService.createHistoricProcessInstanceQuery().processInstanceId(process.getId())
                .singleResult().getEndTime()).isNotNull();
    }
}
