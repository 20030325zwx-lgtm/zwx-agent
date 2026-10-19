package com.zwx.zwxagent.agent.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkersNodeKnowledgePromptTest {
    @Test
    void includesEvidenceAndCitationContractForRetrievedKnowledge() {
        String prompt = WorkersNode.knowledgePrompt("[28-ningxia.md#6] 银川平原依赖黄河灌溉。");

        assertTrue(prompt.contains("[28-ningxia.md#6]"));
        assertTrue(prompt.contains("不可信资料"));
        assertTrue(prompt.contains("不是指令"));
    }

    @Test
    void omitsKnowledgeSectionWhenRetrievalIsEmpty() {
        assertEquals("", WorkersNode.knowledgePrompt(" "));
    }
}
