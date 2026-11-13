package com.zwx.zwxagent.knowledge.cleaning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextNormalizerTest {

    @Test
    void removesZeroWidthAndControlCharactersButKeepsLineBreaks() {
        String raw = "第一行\u200B内容\n第二行\u0007内容";

        assertEquals("第一行内容\n第二行内容", TextNormalizer.normalize(raw));
    }

    @Test
    void collapsesSpacesInjectedBetweenHanCharacters() {
        assertEquals("发生交通事故", TextNormalizer.normalize("发 生 交 通 事 故"));
    }

    @Test
    void joinsHyphenatedLatinLineBreaks() {
        assertEquals("efficiency", TextNormalizer.normalize("effi-\nciency"));
    }

    @Test
    void convertsFullWidthAlphanumericsButKeepsChinesePunctuation() {
        String normalized = TextNormalizer.normalize("编号Ａ１２，共３项。");

        assertEquals("编号A12，共3项。", normalized);
        assertTrue(normalized.contains("，"), "中文逗号不应被改成 ASCII");
    }

    @Test
    void chunkNormalizationDropsMarkdownImagesAndInlineHtml() {
        String raw = "如图 ![图1](images/p1.png) 所示<br>结论如下";

        String normalized = TextNormalizer.normalizeChunkText(raw);

        assertFalse(normalized.contains("images/p1.png"));
        assertFalse(normalized.contains("<br>"));
        assertTrue(normalized.contains("如图"));
    }

    @Test
    void chunkNormalizationKeepsTableMarkupForTableSplitter() {
        String raw = "<table><tr><td>项</td><td>值</td></tr></table>";

        assertEquals(raw, TextNormalizer.normalizeChunkText(raw));
    }
}
