package com.zwx.zwxagent.knowledge.document;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredKnowledgeChunkerTest {

    private MineruBlockParser.MineruBlock block(String id, String type, int page, String text, String caption,
                                                HtmlTableParser.TableData table, boolean heading, int headingLevel) {
        return new MineruBlockParser.MineruBlock(id, type, page, "[0,0,100,100]", text, caption, null, table,
                null, heading, headingLevel, "{}");
    }

    @Test
    void splitsTablesByRowGroupsAndKeepsRegionMetadata() {
        List<List<String>> rows = new ArrayList<>();
        for (int index = 1; index <= 8; index++) {
            rows.add(List.of("撤回流", "检查项 " + index, "通过"));
        }
        HtmlTableParser.TableData table = new HtmlTableParser.TableData("表 1 验收", List.of("流程", "检查项", "结果"), rows);
        List<MineruBlockParser.MineruBlock> blocks = List.of(
                block("b1", AgentKnowledgeBlock.TEXT, 12, "2.3.2 待办功能流程测试", null, null, true, 2),
                block("b2", AgentKnowledgeBlock.TEXT, 12, "本节描述撤回流程。", null, null, false, 0),
                block("b3", AgentKnowledgeBlock.TABLE, 12, "", "表 1 验收", table, false, 0));

        List<Document> chunks = new StructuredKnowledgeChunker().chunks(blocks, Map.of("filename", "handbook.docx"));

        List<Document> tables = chunks.stream().filter(chunk -> AgentKnowledgeBlock.TABLE.equals(chunk.getMetadata().get("sourceType"))).toList();
        assertEquals(2, tables.size());
        assertEquals(0, tables.get(0).getMetadata().get("rowStart"));
        assertEquals(6, tables.get(0).getMetadata().get("rowEnd"));
        assertEquals(6, tables.get(1).getMetadata().get("rowStart"));
        assertEquals(8, tables.get(1).getMetadata().get("rowEnd"));
        assertEquals("表 1 验收 · 第 12 页 · 第 1-6 行", tables.get(0).getMetadata().get("regionLabel"));
        assertTrue(tables.get(0).getText().contains("列：流程 | 检查项 | 结果"));
        assertTrue(tables.get(0).getText().contains("检查项：检查项 1"));
        assertFalse(tables.get(0).getText().contains("<table"));
        assertTrue(tables.get(0).getText().contains("2.3.2 待办功能流程测试"));

        Document text = chunks.stream().filter(chunk -> AgentKnowledgeBlock.TEXT.equals(chunk.getMetadata().get("sourceType"))).findFirst().orElseThrow();
        assertEquals("b2", text.getMetadata().get("blockId"));
        assertEquals(12, text.getMetadata().get("pageNumber"));
        assertEquals("2.3.2 待办功能流程测试", text.getMetadata().get("sectionPath"));
    }

    @Test
    void emitsCaptionChunkForFiguresAndTextBlocks() {
        List<MineruBlockParser.MineruBlock> blocks = List.of(
                block("f1", AgentKnowledgeBlock.FIGURE, 8, "", "图 2 报销审批流程", null, false, 0),
                block("t1", AgentKnowledgeBlock.TEXT, 8, "普通正文", null, null, false, 0));

        List<Document> chunks = new StructuredKnowledgeChunker().chunks(blocks, Map.of());

        assertTrue(chunks.stream().anyMatch(chunk -> AgentKnowledgeBlock.FIGURE.equals(chunk.getMetadata().get("sourceType"))
                && chunk.getText().contains("[图片] 图 2 报销审批流程")));
        assertTrue(chunks.stream().anyMatch(chunk -> AgentKnowledgeBlock.TEXT.equals(chunk.getMetadata().get("sourceType"))
                && chunk.getText().contains("普通正文")));
    }
}
