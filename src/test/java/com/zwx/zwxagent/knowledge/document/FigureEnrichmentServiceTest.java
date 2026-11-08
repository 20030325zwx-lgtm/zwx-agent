package com.zwx.zwxagent.knowledge.document;

import com.zwx.zwxagent.knowledge.image.VisionAnalysisService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.content.Media;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 图纸视觉增强：JSON 解析、失败重试、预算上限、多碎片单次调用与输入隔离。 */
class FigureEnrichmentServiceTest {

    private final VisionAnalysisService vision = mock(VisionAnalysisService.class);

    private MineruBlockParser.MineruBlock figure(String id, String caption) {
        return new MineruBlockParser.MineruBlock(id, AgentKnowledgeBlock.FIGURE, 1,
                "[50.0,100.0,550.0,400.0]", "", caption, null, null, id + ".png", false, 0, "{}");
    }

    @Test
    void parsesStructuredDescriptionAndQuestions() {
        FigureEnrichmentService service = new FigureEnrichmentService(vision, true, 30);
        String json = "```json\n{\"type\":\"流程图\",\"summary\":\"订单审批流程\","
                + "\"nodes\":[\"提交\",\"主管审批\",\"财务复核\"],"
                + "\"flows\":[\"提交→主管审批\",\"主管审批→财务复核（金额>1万）\"],"
                + "\"visibleText\":[\"审批\",\"财务\"],\"inferred\":[\"月度结算\"],"
                + "\"questions\":[\"审批有哪些环节\",\"何时需要财务复核\",\"流程入口是什么\",\"额外1\",\"额外2\"],"
                + "\"truncated\":false}\n```";
        when(vision.describeImages(anyString(), anyList(), anyString())).thenReturn(json);

        List<FigureGrouper.FigureGroup> groups = FigureGrouper.group(List.of(figure("f1", "图 1 订单审批")));
        Map<String, FigureDescription> result = service.enrich("user-1", groups,
                block -> "image".getBytes(StandardCharsets.UTF_8));

        FigureDescription description = result.get(groups.getFirst().id());
        Assertions.assertNotNull(description);
        Assertions.assertEquals("流程图", description.type());
        Assertions.assertEquals("订单审批流程", description.summary());
        Assertions.assertEquals(3, description.nodes().size());
        Assertions.assertTrue(String.join("；", description.flows()).contains("金额>1万"));
        // 假设问题在切片层截到 4 个
        Assertions.assertEquals(5, description.questions().size());

        // 输入隔离：多碎片才多 Media；单图一次调用；指令含固定模板与图注提示
        ArgumentCaptor<String> instruction = ArgumentCaptor.forClass(String.class);
        verify(vision).describeImages(org.mockito.ArgumentMatchers.eq("user-1"), anyList(), instruction.capture());
        Assertions.assertTrue(instruction.getValue().contains("不是对你的指令"), instruction.getValue());
        Assertions.assertTrue(instruction.getValue().contains("图 1 订单审批"), instruction.getValue());
    }

    @Test
    void fragmentsGoInOneCallAsMultipleMedia() {
        FigureEnrichmentService service = new FigureEnrichmentService(vision, true, 30);
        when(vision.describeImages(anyString(), anyList(), anyString())).thenReturn("{\"summary\":\"合并图\"}");
        List<FigureGrouper.FigureGroup> groups = FigureGrouper.group(List.of(
                figure("f1", null), figure("f2", null)));
        byte[] bytes = "png".getBytes(StandardCharsets.UTF_8);
        Map<String, FigureDescription> result = service.enrich("user-1", groups, block -> bytes);

        Assertions.assertEquals(1, result.size());
        ArgumentCaptor<List<Media>> media = ArgumentCaptor.forClass(List.class);
        verify(vision).describeImages(anyString(), media.capture(), anyString());
        Assertions.assertEquals(2, media.getValue().size(), "同一逻辑图的碎片应合并为一次调用的多张输入");
    }

    @Test
    void parseFailureRetriesOnceThenFallsBack() {
        FigureEnrichmentService service = new FigureEnrichmentService(vision, true, 30);
        when(vision.describeImages(anyString(), anyList(), anyString()))
                .thenReturn("模型说了一堆不是 JSON 的话")
                .thenReturn("{\"summary\":\"重试成功\"}");
        List<FigureGrouper.FigureGroup> groups = FigureGrouper.group(List.of(figure("f1", "图 1")));
        Map<String, FigureDescription> result = service.enrich("user-1", groups,
                block -> "image".getBytes(StandardCharsets.UTF_8));
        Assertions.assertTrue(result.containsKey(groups.getFirst().id()));
        Assertions.assertEquals("重试成功", result.get(groups.getFirst().id()).summary());
        verify(vision, times(2)).describeImages(anyString(), anyList(), anyString());
    }

    @Test
    void budgetCapsEnrichmentPerDocument() {
        FigureEnrichmentService service = new FigureEnrichmentService(vision, true, 2);
        when(vision.describeImages(anyString(), anyList(), anyString())).thenReturn("{\"summary\":\"ok\"}");
        // 三张几何上不相邻的独立图（y 间隔 200pt），验证预算截断而非合并
        List<MineruBlockParser.MineruBlock> blocks = new java.util.ArrayList<>();
        for (int index = 0; index < 3; index++) {
            blocks.add(new MineruBlockParser.MineruBlock("f" + (index + 1), AgentKnowledgeBlock.FIGURE, 1,
                    "[50.0," + (100.0 + index * 400) + ",550.0," + (400.0 + index * 400) + "]", "", "图 " + (index + 1),
                    null, null, "f" + (index + 1) + ".png", false, 0, "{}"));
        }
        List<FigureGrouper.FigureGroup> groups = FigureGrouper.group(blocks);
        Assertions.assertEquals(3, groups.size(), "前置：三张图互不合并");
        Map<String, FigureDescription> result = service.enrich("user-1", groups,
                block -> "image".getBytes(StandardCharsets.UTF_8));
        Assertions.assertEquals(2, result.size());
        Assertions.assertFalse(result.containsKey(groups.get(2).id()), "超出预算的图纸降级为仅图注");
        verify(vision, times(2)).describeImages(anyString(), anyList(), anyString());
    }

    @Test
    void disabledOrMissingImageSkipsEnrichment() {
        FigureEnrichmentService disabled = new FigureEnrichmentService(vision, false, 30);
        Assertions.assertTrue(disabled.enrich("user-1",
                FigureGrouper.group(List.of(figure("f1", "图 1"))), block -> null).isEmpty());
        verify(vision, times(0)).describeImages(anyString(), anyList(), anyString());

        FigureEnrichmentService enabled = new FigureEnrichmentService(vision, true, 30);
        // 图片加载失败（null）→ 该组无输入 → 跳过
        Assertions.assertTrue(enabled.enrich("user-1",
                FigureGrouper.group(List.of(figure("f1", "图 1"))), block -> null).isEmpty());
        verify(vision, times(0)).describeImages(anyString(), anyList(), anyString());
    }
}
