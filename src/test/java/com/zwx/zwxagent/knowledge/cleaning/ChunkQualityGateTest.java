package com.zwx.zwxagent.knowledge.cleaning;

import com.zwx.zwxagent.knowledge.document.AgentKnowledgeBlock;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkQualityGateTest {

    private final ChunkQualityGate gate = new ChunkQualityGate(
            new CleaningSettings(true, 12, 0.15d, true, 60, 0.3d, true, 40, 1200));

    private static Document chunk(String id, String text, Map<String, Object> metadata) {
        return Document.builder().id(id).text(text).metadata(metadata).build();
    }

    @Test
    void dropsTooShortTextChunks() {
        List<Document> chunks = List.of(
                chunk("c1", "见图示", Map.of("sourceType", AgentKnowledgeBlock.TEXT)),
                chunk("c2", "发生交通事故后应当立即停车并保护现场。", Map.of("sourceType", AgentKnowledgeBlock.TEXT)));

        ChunkGateResult result = gate.filter(chunks);

        assertEquals(1, result.chunks().size());
        assertEquals("c2", result.chunks().get(0).getId());
        assertEquals(1, result.stats().droppedTooShort());
    }

    @Test
    void keepsShortTableRowsAndFigureQuestions() {
        List<Document> chunks = List.of(
                chunk("t1", "项目：赔偿", Map.of("sourceType", AgentKnowledgeBlock.TABLE,
                        "structuredContentType", "table-record")),
                chunk("q1", "这张图说明了什么？", Map.of("sourceType", AgentKnowledgeBlock.FIGURE,
                        "chunkRole", "figure_question")));

        ChunkGateResult result = gate.filter(chunks);

        assertEquals(2, result.chunks().size());
        assertEquals(0, result.stats().droppedTooShort());
    }

    @Test
    void dropsDuplicateLongChunksWithinDocument() {
        String text = "发生交通事故后，驾驶人应当立即停车、保护现场，开启危险报警闪光灯，"
                + "并迅速报告执勤交通警察或者公安机关交通管理部门，配合现场处置。";
        List<Document> chunks = List.of(
                chunk("c1", text, Map.of("sourceType", AgentKnowledgeBlock.TEXT)),
                chunk("c2", text, Map.of("sourceType", AgentKnowledgeBlock.TEXT)));

        ChunkGateResult result = gate.filter(chunks);

        assertEquals(1, result.chunks().size());
        assertEquals(1, result.stats().droppedDuplicates());
    }

    @Test
    void doesNotDeduplicateTableRowsSharingHeader() {
        String row = "[表格] 表 1\n列：项目 | 金额\n项目：赔偿；金额：100 元";
        List<Document> chunks = List.of(
                chunk("t1", row, Map.of("sourceType", AgentKnowledgeBlock.TABLE)),
                chunk("t2", row, Map.of("sourceType", AgentKnowledgeBlock.TABLE)));

        ChunkGateResult result = gate.filter(chunks);

        assertEquals(2, result.chunks().size());
        assertEquals(0, result.stats().droppedDuplicates());
    }

    @Test
    void normalizesChunkTextAndPreservesMetadataAndId() {
        Document chunk = chunk("c1", "如图 ![图1](images/p1.png) 所示，驾驶人<b>必须</b>立即停车并保护现场。",
                Map.of("sourceType", AgentKnowledgeBlock.TEXT, "chunkIndex", 3));

        ChunkGateResult result = gate.filter(List.of(chunk));

        Document kept = result.chunks().get(0);
        assertEquals("c1", kept.getId());
        assertEquals(3, kept.getMetadata().get("chunkIndex"));
        assertFalse(kept.getText().contains("images/p1.png"));
        assertFalse(kept.getText().contains("<b>"));
        assertTrue(kept.getText().contains("必须"));
    }

    @Test
    void dropsPurePageNumberChunks() {
        List<Document> chunks = List.of(
                chunk("n1", "第 12 页", Map.of("sourceType", AgentKnowledgeBlock.TEXT)),
                chunk("n2", "第 12 页内容：事故处理流程说明如下。", Map.of("sourceType", AgentKnowledgeBlock.TEXT)));

        ChunkGateResult result = gate.filter(chunks);

        assertEquals(1, result.chunks().size());
        assertEquals("n2", result.chunks().get(0).getId());
        assertEquals(1, result.stats().droppedNoise());
    }

    @Test
    void disabledGatePassesEverythingThrough() {
        ChunkQualityGate off = new ChunkQualityGate(
                new CleaningSettings(false, 12, 0.15d, true, 60, 0.3d, true, 40, 1200));

        ChunkGateResult result = off.filter(List.of(chunk("c1", "短", Map.of())));

        assertEquals(1, result.chunks().size());
        assertEquals(0, result.stats().droppedTooShort());
    }
}
