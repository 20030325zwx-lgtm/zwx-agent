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

    @Test
    void filtersStopwordBigramsAndShortEnglishWords() {
        var terms = AgentKnowledgeRagService.lexicalTerms("这个合同的是什么条款，of course 违约责任");
        Assertions.assertFalse(terms.contains("这个"), "虚词 2-gram 应被剔除");
        Assertions.assertFalse(terms.contains("的是"), "虚词 2-gram 应被剔除");
        Assertions.assertFalse(terms.contains("of"), "英文短虚词应被剔除");
        Assertions.assertTrue(terms.contains("合同"));
        Assertions.assertTrue(terms.contains("违约"));
    }

    @Test
    void capsTermsAt32() {
        // 47 个汉字 → 46 个 2-gram，截到 32
        String longQuestion = "甲乙双方就项目开发达成协议付款条款验收标准保密义务知识产权归属争议解决方式适用法律合同期限变更程序";
        var terms = AgentKnowledgeRagService.lexicalTerms(longQuestion);
        Assertions.assertEquals(32, terms.size());
        Assertions.assertTrue(terms.contains("付款"), "前半段词项保留");
        Assertions.assertTrue(terms.contains("保密"), "32 上限应覆盖约 60 字问题，比旧 20 更少丢词");
    }
}
