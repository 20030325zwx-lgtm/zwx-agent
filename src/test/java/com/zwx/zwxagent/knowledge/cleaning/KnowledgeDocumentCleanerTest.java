package com.zwx.zwxagent.knowledge.cleaning;

import com.zwx.zwxagent.knowledge.document.AgentKnowledgeBlock;
import com.zwx.zwxagent.knowledge.document.HtmlTableParser;
import com.zwx.zwxagent.knowledge.document.MineruBlockParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeDocumentCleanerTest {

    private final KnowledgeDocumentCleaner cleaner = new KnowledgeDocumentCleaner(
            settings(true, 0.15d, true, true),
            new FurnitureDetector(settings(true, 0.15d, true, true)),
            new ParagraphMerger(settings(true, 0.15d, true, true)));

    private static CleaningSettings settings(boolean enabled, double maxDeletionRatio,
                                             boolean furniture, boolean dedupe) {
        return new CleaningSettings(enabled, 12, maxDeletionRatio, furniture, 60, 0.3d, dedupe, 40, 1200);
    }

    private static MineruBlockParser.MineruBlock text(String id, int page, String content) {
        return block(id, AgentKnowledgeBlock.TEXT, page, content, false, 0, null);
    }

    private static MineruBlockParser.MineruBlock heading(String id, int page, String content) {
        return block(id, AgentKnowledgeBlock.TEXT, page, content, true, 1, null);
    }

    private static MineruBlockParser.MineruBlock block(String id, String type, int page, String content,
                                                       boolean heading, int level, HtmlTableParser.TableData table) {
        return new MineruBlockParser.MineruBlock(id, type, page, "[0,0,100,100]", content, null,
                type.equals(AgentKnowledgeBlock.TABLE) ? "<table></table>" : null, table, null, heading, level, "{}");
    }

    @Test
    void dropsPageNumbersAndSymbolNoiseButKeepsContent() {
        List<MineruBlockParser.MineruBlock> blocks = List.of(
                text("b1", 1, "本规范适用于交通事故处理。"),
                text("b2", 1, "第 1 页"),
                text("b3", 2, "————"),
                text("b4", 2, "事故现场应当立即保护。"));

        BlockCleaningResult result = cleaner.clean(blocks);

        assertEquals(List.of("b1", "b4"), result.blocks().stream().map(MineruBlockParser.MineruBlock::blockId).toList());
        assertEquals(2, result.stats().droppedNoise());
        assertTrue(result.records().get("b2").dropped());
        assertTrue(result.records().get("b2").actions().contains(CleaningAction.DROPPED_PAGE_NUMBER));
        assertFalse(result.stats().rollbackApplied());
    }

    @Test
    void dropsRepeatedPageHeaderAcrossPagesButKeepsRepeatedHeading() {
        List<MineruBlockParser.MineruBlock> blocks = new ArrayList<>();
        for (int page = 1; page <= 6; page++) {
            blocks.add(heading("h" + page, page, "第一章 总则"));
            blocks.add(text("t" + page, page, "正文内容第" + page
                    + "段，描述事故处理业务流程、现场保护要求、报警与取证步骤，以及后续保险理赔的衔接安排。"));
            blocks.add(text("f" + page, page, "XX公司 内部资料 第 " + page + " 页"));
        }
        CleaningSettings settings = settings(true, 0.3d, true, true);
        KnowledgeDocumentCleaner cleaner = new KnowledgeDocumentCleaner(settings,
                new FurnitureDetector(settings), new ParagraphMerger(settings));

        BlockCleaningResult result = cleaner.clean(blocks);

        List<String> kept = result.blocks().stream().map(MineruBlockParser.MineruBlock::blockId).toList();
        assertTrue(kept.contains("h1"), "跨页重复的标题不能被当成页眉删掉");
        assertTrue(kept.contains("t3"));
        assertFalse(kept.contains("f1"));
        assertFalse(kept.contains("f6"));
        assertEquals(6, result.stats().droppedFurniture());
        assertTrue(result.records().get("f2").actions().contains(CleaningAction.DROPPED_FURNITURE));
    }

    @Test
    void mergesBrokenParagraphOnSamePageOnly() {
        List<MineruBlockParser.MineruBlock> blocks = List.of(
                text("a1", 3, "发生交通事故时，应立即"),
                text("a2", 3, "启动应急响应并保护现场。"),
                text("a3", 4, "下一章的段落不带句号"),
                text("a4", 5, "这是另一页的内容。"));

        BlockCleaningResult result = cleaner.clean(blocks);

        List<MineruBlockParser.MineruBlock> kept = result.blocks();
        assertEquals(3, kept.size());
        assertEquals("发生交通事故时，应立即启动应急响应并保护现场。", kept.get(0).text());
        assertEquals("a1", kept.get(0).blockId());
        assertEquals(3, kept.get(0).pageNumber());
        assertEquals(1, result.stats().mergedParagraphs());
        // 跨页不合并：a3 与 a4 页码不同，保持两块
        assertEquals("a3", kept.get(1).blockId());
        assertEquals("a4", kept.get(2).blockId());
    }

    @Test
    void doesNotMergeListItemsIntoParagraph() {
        List<MineruBlockParser.MineruBlock> blocks = List.of(
                text("p1", 2, "办理流程如下"),
                text("p2", 2, "1、提交申请材料"),
                text("p3", 2, "2、窗口受理审核"));

        BlockCleaningResult result = cleaner.clean(blocks);

        assertEquals(3, result.blocks().size());
        assertEquals(0, result.stats().mergedParagraphs());
    }

    @Test
    void leavesTableAndEquationBlocksUntouched() {
        HtmlTableParser.TableData table = new HtmlTableParser.TableData("表 1 金额",
                List.of("项目", "金额"), List.of(List.of("赔偿", "12,345.67 元")));
        List<MineruBlockParser.MineruBlock> blocks = List.of(
                block("tbl", AgentKnowledgeBlock.TABLE, 6, "", false, 0, table),
                block("eq", AgentKnowledgeBlock.EQUATION, 6, "E = mc^2", false, 0, null),
                text("p1", 6, "上式说明质量与能量等价，具体推导见附录。"));

        BlockCleaningResult result = cleaner.clean(blocks);

        MineruBlockParser.MineruBlock tableBlock = result.blocks().get(0);
        assertEquals("<table></table>", tableBlock.html());
        assertEquals(table, tableBlock.table());
        assertEquals("E = mc^2", result.blocks().get(1).text(), "公式块不做空白压缩与段落合并");
        assertEquals(3, result.blocks().size(), "公式不能被并进正文段落");
    }

    @Test
    void removesExactDuplicateLongParagraphs() {
        String paragraph = "发生交通事故后，驾驶人应当立即停车、保护现场，开启危险报警闪光灯，"
                + "并迅速报告执勤交通警察或者公安机关交通管理部门，配合现场处置。";
        List<MineruBlockParser.MineruBlock> blocks = List.of(
                text("d1", 7, paragraph),
                text("d2", 8, paragraph));

        BlockCleaningResult result = cleaner.clean(blocks);

        assertEquals(1, result.blocks().size());
        assertEquals("d1", result.blocks().get(0).blockId());
        assertEquals(1, result.stats().droppedDuplicates());
        assertTrue(result.records().get("d2").actions().contains(CleaningAction.DROPPED_DUPLICATE));
    }

    @Test
    void rollsBackWholeCleaningWhenDeletionRatioExceedsLimit() {
        List<MineruBlockParser.MineruBlock> blocks = new ArrayList<>();
        blocks.add(text("keep", 1, "正文内容：交通事故处理流程说明，包含现场保护、报警、取证与理赔衔接等要求。"));
        for (int page = 1; page <= 60; page++) {
            blocks.add(text("page" + page, page, "第 " + page + " 页"));
        }

        BlockCleaningResult result = cleaner.clean(blocks);

        assertTrue(result.stats().rollbackApplied());
        assertEquals(61, result.blocks().size(), "回滚时必须返回原始块");
        assertTrue(result.records().isEmpty());
    }

    @Test
    void disabledCleaningPassesBlocksThroughUnchanged() {
        CleaningSettings disabled = settings(false, 0.15d, true, true);
        KnowledgeDocumentCleaner off = new KnowledgeDocumentCleaner(disabled,
                new FurnitureDetector(disabled), new ParagraphMerger(disabled));
        List<MineruBlockParser.MineruBlock> blocks = List.of(text("b1", 1, "第 1 页"));

        BlockCleaningResult result = off.clean(blocks);

        assertEquals(1, result.blocks().size());
        assertTrue(result.records().isEmpty());
        assertEquals(0, result.stats().textBlockCount());
    }

    @Test
    void flagsGarbledTextForReviewWithoutDroppingIt() {
        List<MineruBlockParser.MineruBlock> blocks = List.of(
                text("g1", 1, "正常内容段落。" ),
                text("g2", 1, "事故处理流程如下：\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD"));

        BlockCleaningResult result = cleaner.clean(blocks);

        assertEquals(2, result.blocks().size());
        assertTrue(result.records().get("g2").reviewRequired());
        assertTrue(result.records().get("g2").actions().contains(CleaningAction.GARBLED_REVIEW));
        assertEquals(1, result.stats().garbledBlocks());
    }
}
