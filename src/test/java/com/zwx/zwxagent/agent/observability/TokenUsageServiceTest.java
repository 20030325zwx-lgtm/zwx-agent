package com.zwx.zwxagent.agent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TokenUsageServiceTest {

    private final TokenUsageService service = new TokenUsageService(new ObjectMapper());

    private static TokenUsageService.CallSample sample(int prompt, int completion) {
        return new TokenUsageService.CallSample(prompt, completion, 1000, 200, 3, 500, 4, 9000);
    }

    @Test
    void aggregatesTokensPerPhaseAndTotals() {
        service.record("run-1", "planner", sample(700, 30));
        service.record("run-1", "worker·研究员#1", sample(3000, 120));
        service.record("run-1", "worker·研究员#1", sample(3200, 90));

        assertEquals(6900, service.promptTokens("run-1"));
        assertEquals(240, service.completionTokens("run-1"));
        assertEquals(3, service.calls("run-1"));
    }

    @Test
    void ordersPhasesByPromptTokensDescending() throws Exception {
        service.record("run-2", "planner", sample(100, 5));
        service.record("run-2", "worker·通用#1", sample(4000, 200));
        service.record("run-2", "verifier", sample(900, 40));
        service.bindContext("run-2", 1234);

        Map<String, Object> root = new ObjectMapper().readValue(service.usageJson("run-2"), Map.class);
        assertEquals(5000, root.get("promptTokens"));
        assertEquals(1234, root.get("ragContextChars"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> phases = (List<Map<String, Object>>) root.get("phases");
        assertEquals(List.of("worker·通用#1", "verifier", "planner"),
                phases.stream().map(phase -> phase.get("phase")).toList());
        Map<String, Object> top = phases.get(0);
        assertEquals(1, top.get("calls"));
        assertEquals(200, top.get("avgToolSchemaChars"));
        assertEquals(3, top.get("maxToolCount"));
        assertEquals(500, top.get("avgMessageChars"));
        assertEquals(9000, top.get("avgToolResultChars"));
        assertEquals(9000, top.get("maxToolResultChars"));
    }

    @Test
    void isolatesRunsAndClearsOnRelease() {
        service.record("run-3", "planner", sample(700, 30));
        service.record("run-4", "planner", sample(100, 10));

        service.release("run-3");
        assertEquals(0, service.calls("run-3"));
        assertNull(service.usageJson("run-3"));
        assertEquals(100, service.promptTokens("run-4"));
    }

    @Test
    void ignoresBlankRunIdAndMissingLedger() {
        service.record("", "planner", sample(700, 30));
        service.record(null, "planner", sample(700, 30));

        assertEquals(0, service.calls("never-seen"));
        assertEquals(0, service.promptTokens(null));
        assertNull(service.usageJson("never-seen"));
    }
}
