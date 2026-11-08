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
        assertEquals(8, tables.size());
        assertEquals(0, tables.get(0).getMetadata().get("rowStart"));
        assertEquals(1, tables.get(0).getMetadata().get("rowEnd"));
        assertEquals(7, tables.get(7).getMetadata().get("rowStart"));
        assertEquals(8, tables.get(7).getMetadata().get("rowEnd"));
        assertEquals("表 1 验收 · 第 12 页 · 第 1 行", tables.get(0).getMetadata().get("regionLabel"));
        assertTrue(tables.get(0).getText().contains("列：流程 | 检查项 | 结果"));
        assertTrue(tables.get(0).getText().contains("检查项：检查项 1"));
        assertFalse(tables.get(0).getText().contains("<table"));
        assertTrue(tables.get(0).getText().contains("2.3.2 待办功能流程测试"));
        assertTrue(tables.get(1).getText().contains("检查项：检查项 2"));
        assertFalse(tables.get(1).getText().contains("检查项：检查项 1"));

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

    @Test
    void enrichedFigureProducesDescriptionAndQuestionChunks() {
        // 有描述：图纸语义整体可检索；无图注也由描述兜底
        MineruBlockParser.MineruBlock figure = block("f1", AgentKnowledgeBlock.FIGURE, 5, "", null, null, false, 0);
        FigureDescription description = new FigureDescription("流程图", "报销审批流程：提交→主管审批→财务",
                List.of("提交", "主管审批", "财务"), List.of("提交→主管审批"), List.of("审批"),
                List.of("月度报销场景"), List.of("报销流程有哪些环节", "审批入口是什么"), false);
        List<FigureGrouper.FigureGroup> groups = FigureGrouper.group(List.of(figure));
        FigureSupport support = FigureSupport.of(groups, Map.of(groups.getFirst().id(), description));

        List<Document> chunks = new StructuredKnowledgeChunker().chunks(List.of(figure), Map.of(), support);

        Document desc = chunks.stream().filter(chunk -> "figure_description".equals(chunk.getMetadata().get("chunkRole")))
                .findFirst().orElseThrow();
        assertTrue(desc.getText().contains("[图纸|流程图]"));
        assertTrue(desc.getText().contains("摘要：报销审批流程"));
        assertTrue(desc.getText().contains("流向：提交→主管审批"));
        assertTrue(desc.getText().contains("推断（未核实）"));
        assertEquals("流程图", desc.getMetadata().get("figureType"));
        assertEquals(Boolean.FALSE, desc.getMetadata().get("figureTruncated"));

        List<Document> questions = chunks.stream()
                .filter(chunk -> "figure_question".equals(chunk.getMetadata().get("chunkRole"))).toList();
        assertEquals(2, questions.size());
        assertTrue(questions.get(0).getText().contains("报销流程有哪些环节"));
    }

    @Test
    void figureWithoutCaptionAndDescriptionIsSkipped() {
        // 增强不可用（NONE）且无图注：无任何可检索语义，不再产出空壳 chunk
        MineruBlockParser.MineruBlock figure = block("f1", AgentKnowledgeBlock.FIGURE, 5, "", null, null, false, 0);
        List<Document> chunks = new StructuredKnowledgeChunker().chunks(List.of(figure), Map.of(), FigureSupport.NONE);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void mergedFragmentsEmitSingleDescriptionChunk() {
        // 大图防碎：合并组只产出一组 chunk，metadata 记录碎片数与图片清单
        List<MineruBlockParser.MineruBlock> fragments = List.of(
                new MineruBlockParser.MineruBlock("f1", AgentKnowledgeBlock.FIGURE, 5, "[0,0,100,100]", "",
                        "图 3 部署流程", null, null, "f1.png", false, 0, "{}"),
                new MineruBlockParser.MineruBlock("f2", AgentKnowledgeBlock.FIGURE, 5, "[0,0,100,100]", "",
                        null, null, null, "f2.png", false, 0, "{}"));
        FigureDescription description = new FigureDescription("流程图", "部署全流程", List.of(), List.of("构建→灰度→全量"),
                List.of(), List.of(), List.of(), false);
        List<FigureGrouper.FigureGroup> groups = FigureGrouper.group(fragments);
        FigureSupport support = FigureSupport.of(groups, Map.of(groups.getFirst().id(), description));

        List<Document> chunks = new StructuredKnowledgeChunker().chunks(fragments, Map.of(), support);

        long descCount = chunks.stream().filter(chunk -> "figure_description".equals(chunk.getMetadata().get("chunkRole"))).count();
        assertEquals(1, descCount, "合并后的逻辑图只产出一个描述 chunk");
        Document desc = chunks.stream().filter(chunk -> "figure_description".equals(chunk.getMetadata().get("chunkRole")))
                .findFirst().orElseThrow();
        assertEquals(2, desc.getMetadata().get("figureFragments"));
        assertEquals("f1.png|f2.png", desc.getMetadata().get("figureImages"));
    }
}
