package com.zwx.zwxagent.knowledge.retrieval;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentKnowledgeRagServiceLexicalTermsTest {

    @Test
    void includesKeyChineseBigramsWithoutWastingSlotOnFullSentence() {
        var terms = AgentKnowledgeRagService.lexicalTerms("先维员工每个月可以迟到几次不扣钱");
        Assertions.assertTrue(terms.contains("迟到"));
        Assertions.assertTrue(terms.contains("扣钱"));
        Assertions.assertFalse(terms.contains("先维员工每个月可以迟到几次不扣钱"));
    }

    @Test
    void keepsAsciiTermsAndDropsSingleCharacters() {
        var terms = AgentKnowledgeRagService.lexicalTerms("HTTP 500 错误");
        Assertions.assertTrue(terms.contains("http"));
        Assertions.assertTrue(terms.contains("500"));
    }
}
