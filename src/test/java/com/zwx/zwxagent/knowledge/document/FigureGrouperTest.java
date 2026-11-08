package com.zwx.zwxagent.knowledge.document;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/** 大图防碎合并规则：几何紧邻/图注信号合并，正文隔断与远距不合并，组上限生效。 */
class FigureGrouperTest {

    private MineruBlockParser.MineruBlock figure(String id, int page, String bbox, String caption) {
        return new MineruBlockParser.MineruBlock(id, AgentKnowledgeBlock.FIGURE, page, bbox, "", caption,
                null, null, id + ".jpg", false, 0, "{}");
    }

    private MineruBlockParser.MineruBlock text(String id, int page, String text) {
        return new MineruBlockParser.MineruBlock(id, AgentKnowledgeBlock.TEXT, page, "[0,0,100,50]", text,
                null, null, null, null, false, 0, "{}");
    }

    @Test
    void verticallyStackedFragmentsMerge() {
        // 同一张大流程图被上下切成两半：X 轴完全重叠，Y 向间隔 10pt < 15pt
        List<FigureGrouper.FigureGroup> groups = FigureGrouper.group(List.of(
                figure("f1", 3, "[50.0,100.0,550.0,400.0]", null),
                figure("f2", 3, "[50.0,410.0,550.0,760.0]", null)));
        Assertions.assertEquals(1, groups.size());
        Assertions.assertEquals(2, groups.getFirst().blocks().size());
        Assertions.assertEquals("fg-1", groups.getFirst().id());
    }

    @Test
    void distantFiguresStaySeparate() {
        // 间隔 200pt：两张独立的图
        List<FigureGrouper.FigureGroup> groups = FigureGrouper.group(List.of(
                figure("f1", 3, "[50.0,100.0,550.0,400.0]", "图 1"),
                figure("f2", 3, "[50.0,600.0,550.0,900.0]", "图 2")));
        Assertions.assertEquals(2, groups.size());
    }

    @Test
    void sideBySideFiguresWithWideGapStaySeparate() {
        // 左右两图，X 向间隔 30pt > 15pt
        List<FigureGrouper.FigureGroup> groups = FigureGrouper.group(List.of(
                figure("f1", 3, "[50.0,100.0,250.0,700.0]", "图 A"),
                figure("f2", 3, "[280.0,100.0,480.0,700.0]", "图 B")));
        Assertions.assertEquals(2, groups.size());
    }

    @Test
    void textBlockBetweenFiguresBreaksMergeRun() {
        // 中间隔着正文：即便几何紧邻也不合并（正文是语义隔断）
        List<FigureGrouper.FigureGroup> groups = FigureGrouper.group(List.of(
                figure("f1", 3, "[50.0,100.0,550.0,400.0]", null),
                text("t1", 3, "图 1 与图 2 之间的说明文字"),
                figure("f2", 3, "[50.0,410.0,550.0,760.0]", null)));
        Assertions.assertEquals(2, groups.size());
    }

    @Test
    void continuationCaptionMergesAcrossPages() {
        // 跨页大图的唯一合法跨页合并：显式「(续)」图注信号
        List<FigureGrouper.FigureGroup> groups = FigureGrouper.group(List.of(
                figure("f1", 3, "[50.0,100.0,550.0,700.0]", "图 3 全量发布流程"),
                figure("f2", 4, "[50.0,100.0,550.0,700.0]", "图 3 全量发布流程（续）")));
        Assertions.assertEquals(1, groups.size());
        Assertions.assertEquals(2, groups.getFirst().blocks().size());
    }

    @Test
    void sameCaptionSamePageMergesEvenWithoutGeometry() {
        List<FigureGrouper.FigureGroup> groups = FigureGrouper.group(List.of(
                figure("f1", 3, "[50.0,100.0,550.0,400.0]", "图 3 发布流程"),
                figure("f2", 3, "[100.0,415.0,600.0,800.0]", "图 3 发布流程")));
        Assertions.assertEquals(1, groups.size());
    }

    @Test
    void groupSizeCapPreventsRunawayMerge() {
        List<MineruBlockParser.MineruBlock> blocks = new java.util.ArrayList<>();
        for (int index = 0; index < 6; index++) {
            blocks.add(figure("f" + index, 3,
                    "[50.0," + (100.0 + index * 305) + ",550.0," + (395.0 + index * 305) + "]", null));
        }
        List<FigureGrouper.FigureGroup> groups = FigureGrouper.group(blocks);
        Assertions.assertEquals(2, groups.size());
        Assertions.assertEquals(4, groups.getFirst().blocks().size());
        Assertions.assertEquals(2, groups.get(1).blocks().size());
    }
}
