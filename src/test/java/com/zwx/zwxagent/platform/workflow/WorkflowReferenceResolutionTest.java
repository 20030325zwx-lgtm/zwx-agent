package com.zwx.zwxagent.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.ExecutorService;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class WorkflowReferenceResolutionTest {
    @Test
    void defersExecutionUntilUpstreamOutputTransactionCommits() {
        WorkflowService service = mock(WorkflowService.class, CALLS_REAL_METHODS);
        ExecutorService executor = mock(ExecutorService.class);
        ReflectionTestUtils.setField(service, "executor", executor);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            ReflectionTestUtils.invokeMethod(service, "submitAfterCommit", (Object) null);
            verifyNoInteractions(executor);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(executor).submit(any(Runnable.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void resolvesUuidMediaReferencesInsideListsAndPreservesLiteralReplacementCharacters() {
        WorkflowService service = mock(WorkflowService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        String node = "b45b48fd-38ae-43bb-bd9a-377ab0f2d199";
        Map<String, Object> params = Map.of("imageAssetIds", List.of("{{" + node + ".assetId}}"),
                "prompt", "前缀 {{input.prompt}} 后缀");
        Map<String, Object> resolved = ReflectionTestUtils.invokeMethod(service, "resolveParams", params,
                Map.of("prompt", "$5 \\ sample"), Map.of(node, "{\"assetId\":\"resolved-asset\"}"));
        assertEquals(List.of("resolved-asset"), resolved.get("imageAssetIds"));
        assertEquals("前缀 $5 \\ sample 后缀", resolved.get("prompt"));
    }
}
