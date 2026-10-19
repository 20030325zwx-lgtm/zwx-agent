package com.zwx.zwxagent.agent.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregatorNodeUserAnswerTest {
    @Test
    void systemPromptForbidsInternalExecutionAndVerificationReports() throws Exception {
        assertTrue(AggregatorNode.SYSTEM_PROMPT.contains("严格禁止输出规划、分工、搜索/检索过程"));
        assertTrue(AggregatorNode.SYSTEM_PROMPT.contains("团队工作总结"));

        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/zwx/zwxagent/agent/graph/AggregatorNode.java"));
        assertTrue(source.contains("禁止向用户复述验收状态或添加状态前缀"));
        assertFalse(source.contains("finalAnswer = \"[未验收]"));
    }
}
