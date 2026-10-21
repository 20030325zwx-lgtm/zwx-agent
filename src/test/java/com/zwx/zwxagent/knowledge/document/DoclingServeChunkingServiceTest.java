package com.zwx.zwxagent.knowledge.document;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

class DoclingServeChunkingServiceTest {

    @Test
    void mapsDoclingChunkStructureIntoDocumentMetadata() {
        DoclingServeClient client = Mockito.mock(DoclingServeClient.class);
        Mockito.when(client.chunk(Mockito.any(), Mockito.eq("guide.pdf"))).thenReturn(new DoclingChunkResponse(List.of(
                new DoclingChunk(0, "第一章内容", null, 120,
                        List.of("指南", "第一章"), null, List.of("#/texts/1"), List.of(2),
                        Map.of("origin", Map.of("mimetype", "application/pdf"))),
                new DoclingChunk(1, "第二章内容", null, 80,
                        List.of("指南", "第二章"), null, List.of("#/texts/2"), List.of(3), null)
        ), 1.2));
        DoclingServeChunkingService service = new DoclingServeChunkingService(client, new DoclingDocumentPreprocessor(10, 1000), true, true, "merge");

        List<Document> chunks = service.chunk(new byte[]{1}, "guide.pdf", Map.of("tenantId", "default"));

        Assertions.assertEquals(2, chunks.size());
        Assertions.assertEquals("指南 > 第一章", chunks.get(0).getMetadata().get("sectionPath"));
        Assertions.assertEquals(2, chunks.get(0).getMetadata().get("pageNumber"));
        Assertions.assertEquals("docling-serve", chunks.get(0).getMetadata().get("parser"));
        Assertions.assertEquals("docling-hybrid", chunks.get(0).getMetadata().get("chunking"));
        Assertions.assertEquals("default", chunks.get(0).getMetadata().get("tenantId"));
    }

    @Test
    void emptyDoclingResponseIsRejected() {
        DoclingServeClient client = Mockito.mock(DoclingServeClient.class);
        Mockito.when(client.chunk(Mockito.any(), Mockito.anyString()))
                .thenReturn(new DoclingChunkResponse(List.of(), 0.1));
        DoclingServeChunkingService service = new DoclingServeChunkingService(client, new DoclingDocumentPreprocessor(10, 1000), true, true, "merge");

        Assertions.assertThrows(DoclingServeException.class,
                () -> service.chunk(new byte[]{1}, "empty.pdf", Map.of()));
    }

    @Test
    void addsFurnitureChunkForResumeContactInformation() {
        DoclingServeClient client = Mockito.mock(DoclingServeClient.class);
        Map<String, Object> jsonContent = Map.of("texts", List.of(
                Map.of("label", "page_header", "content_layer", "furniture", "text", "17768576135"),
                Map.of("label", "page_header", "content_layer", "furniture", "text", "1796866501@qq.com"),
                Map.of("label", "page_footer", "content_layer", "furniture", "text", "第 1 页")
        ));
        DoclingChunk body = new DoclingChunk(0, "负责 Java 后端开发", null, 30,
                List.of("工作经历"), null, List.of("#/texts/1"), List.of(1), null);
        Mockito.when(client.chunk(Mockito.any(), Mockito.eq("朱文轩-简历.pdf")))
                .thenReturn(new DoclingChunkResponse(List.of(body), 1.0,
                        List.of(new DoclingDocumentResult(Map.of("json_content", jsonContent)))));
        DoclingServeChunkingService service = new DoclingServeChunkingService(
                client, new DoclingDocumentPreprocessor(10, 1000), true, true, "merge");

        List<Document> chunks = service.chunk(new byte[]{1}, "朱文轩-简历.pdf",
                Map.of("filename", "朱文轩-简历.pdf", "tenantId", "default"));

        Assertions.assertEquals(2, chunks.size());
        Assertions.assertTrue(chunks.get(0).getText().contains("17768576135"));
        Assertions.assertTrue(chunks.get(0).getText().contains("1796866501@qq.com"));
        Assertions.assertFalse(chunks.get(0).getText().contains("第 1 页"));
        Assertions.assertEquals("基本信息", chunks.get(0).getMetadata().get("sectionPath"));
        Assertions.assertEquals("朱文轩-简历.pdf", chunks.get(0).getMetadata().get("filename"));
        Assertions.assertEquals("工作经历", chunks.get(1).getMetadata().get("resumeSection"));
    }

    @Test
    void mergesAndCleansTableOfContentsChunks() {
        DoclingServeClient client = Mockito.mock(DoclingServeClient.class);
        Mockito.when(client.chunk(Mockito.any(), Mockito.eq("standard.pdf")))
                .thenReturn(new DoclingChunkResponse(tocChunks(), 2.0));
        DoclingServeChunkingService service = new DoclingServeChunkingService(
                client, new DoclingDocumentPreprocessor(10, 1000), true, true, "merge");

        List<Document> chunks = service.chunk(new byte[]{1}, "standard.pdf", Map.of("tenantId", "default"));

        Assertions.assertEquals(2, chunks.size());
        Document toc = chunks.get(0);
        Assertions.assertTrue(toc.getText().startsWith("[目录]\n"));
        Assertions.assertFalse(toc.getText().contains("...."));
        Assertions.assertTrue(toc.getText().contains("前言 II"));
        Assertions.assertTrue(toc.getText().contains("3 术语和定义 2"));
        Assertions.assertFalse(toc.getText().contains("目 次"));
        Assertions.assertEquals("docling-toc", toc.getMetadata().get("chunking"));
        Assertions.assertEquals("toc", toc.getMetadata().get("contentLayer"));
        Assertions.assertEquals(4, toc.getMetadata().get("originalChunkCount"));
        Assertions.assertEquals(java.util.Set.of(3), toc.getMetadata().get("pageNumbers"));
        Assertions.assertEquals("前 言", chunks.get(1).getMetadata().get("sectionPath"));
    }

    @Test
    void dropsTableOfContentsChunksWhenConfigured() {
        DoclingServeClient client = Mockito.mock(DoclingServeClient.class);
        Mockito.when(client.chunk(Mockito.any(), Mockito.eq("standard.pdf")))
                .thenReturn(new DoclingChunkResponse(tocChunks(), 2.0));
        DoclingServeChunkingService service = new DoclingServeChunkingService(
                client, new DoclingDocumentPreprocessor(10, 1000), true, true, "drop");

        List<Document> chunks = service.chunk(new byte[]{1}, "standard.pdf", Map.of("tenantId", "default"));

        Assertions.assertEquals(1, chunks.size());
        Assertions.assertEquals("前 言", chunks.get(0).getMetadata().get("sectionPath"));
    }

    @Test
    void rebuildsTocFromDoclingTableCellsWhenChunkTextIsGarbled() {
        DoclingServeClient client = Mockito.mock(DoclingServeClient.class);
        List<Map<String, Object>> cells = List.of(
                Map.of("text", "前言"), Map.of("text", "................................"), Map.of("text", "III"),
                Map.of("text", "1"), Map.of("text", "范围 ................................ 1"),
                Map.of("text", "2"), Map.of("text", "规范性引用文件 ...................... 1"),
                Map.of("text", "7 事故调查评估"), Map.of("text", "................"), Map.of("text", "13"),
                Map.of("text", "附录A（资料性）"), Map.of("text", "人民政府关于成立事故调查组通知示例 14"),
                Map.of("text", "附录U（资料性）"), Map.of("text", "事故整改和防范措施落实情况评估报告 39")
        );
        Map<String, Object> jsonContent = Map.of(
                "tables", List.of(Map.of("data", Map.of("table_cells", cells))),
                "texts", List.of(Map.of("text", "DB45/T 2628-2023 参考文献 ................................ 40")));
        List<DoclingChunk> source = List.of(
                new DoclingChunk(3, "目 次\n| - |\n| 1, = 范围 1, III = 1. 2,", null, 180,
                        List.of("目 次"), null, List.of("#/tables/0", "#/texts/0"), List.of(3), null),
                new DoclingChunk(4, "目 次\n= 规范性引用文件 2, III = 1. 3,", null, 170,
                        List.of("目 次"), null, List.of("#/tables/0"), List.of(3), null)
        );
        Mockito.when(client.chunk(Mockito.any(), Mockito.eq("standard.pdf")))
                .thenReturn(new DoclingChunkResponse(source, 2.0,
                        List.of(new DoclingDocumentResult(Map.of("json_content", jsonContent)))));
        DoclingServeChunkingService service = new DoclingServeChunkingService(
                client, new DoclingDocumentPreprocessor(10, 1000), true, true, "merge");

        List<Document> chunks = service.chunk(new byte[]{1}, "standard.pdf", Map.of("tenantId", "default"));

        Assertions.assertEquals(1, chunks.size());
        String text = chunks.get(0).getText();
        Assertions.assertTrue(text.startsWith("[目录]\n"));
        Assertions.assertTrue(text.contains("前言 III"));
        Assertions.assertTrue(text.contains("1 范围 1"));
        Assertions.assertTrue(text.contains("7 事故调查评估 13"));
        Assertions.assertTrue(text.contains("附录A（资料性） 人民政府关于成立事故调查组通知示例 14"));
        Assertions.assertTrue(text.contains("参考文献 40"));
        Assertions.assertEquals("docling-table", chunks.get(0).getMetadata().get("tocSource"));
        Assertions.assertEquals("toc", chunks.get(0).getMetadata().get("contentLayer"));
    }

    @Test
    void rebuildsChineseChapterAndSectionTocLabels() {
        DoclingServeClient client = Mockito.mock(DoclingServeClient.class);
        List<Map<String, Object>> cells = List.of(
                Map.of("text", "第二章 公司介绍"), Map.of("text", "1"),
                Map.of("text", "第 三章 人事管理制度"), Map.of("text", "1"),
                Map.of("text", "第一节 考勤管理"), Map.of("text", "第一条 作息管理"), Map.of("text", "2"),
                Map.of("text", "第二节 聘用及岗位管理"), Map.of("text", "第一条 试用期管理"), Map.of("text", "3")
        );
        Map<String, Object> jsonContent = Map.of("tables", List.of(
                Map.of("data", Map.of("table_cells", cells))));
        List<DoclingChunk> source = List.of(
                new DoclingChunk(2, "目 次\n第二章 公司介绍 1", null, 120,
                        List.of("目 次"), null, List.of("#/tables/0"), List.of(2), null));
        Mockito.when(client.chunk(Mockito.any(), Mockito.eq("employee.pdf")))
                .thenReturn(new DoclingChunkResponse(source, 1.5,
                        List.of(new DoclingDocumentResult(Map.of("json_content", jsonContent)))));
        DoclingServeChunkingService service = new DoclingServeChunkingService(
                client, new DoclingDocumentPreprocessor(10, 1000), true, true, "merge");

        List<Document> chunks = service.chunk(new byte[]{1}, "employee.pdf", Map.of());

        String text = chunks.get(0).getText();
        Assertions.assertTrue(text.contains("第二章 公司介绍 1"));
        Assertions.assertTrue(text.contains("第三章 人事管理制度 1"));
        Assertions.assertTrue(text.contains("第一节 考勤管理"));
        Assertions.assertTrue(text.contains("第一条 作息管理 2"));
        Assertions.assertTrue(text.contains("第二节 聘用及岗位管理"));
        Assertions.assertTrue(text.contains("第一条 试用期管理 3"));
    }

    private List<DoclingChunk> tocChunks() {
        return List.of(
                new DoclingChunk(3, "目 次\n前言 .................. II\n1 范围 ................ 1\n2 规范性引用文件 ...... 1",
                        null, 250, List.of("目 次"), null, List.of("#/texts/12", "#/texts/13", "#/texts/14"), List.of(3), null),
                new DoclingChunk(4, "目 次\n3 术语和定义 .......... 2\n4 基本要求 ............ 2",
                        null, 242, List.of("目 次"), null, List.of("#/texts/15", "#/texts/16"), List.of(3), null),
                new DoclingChunk(5, "目 次\n5 职业病危害因素识别 .. 3\n6 预防控制措施 ........ 5",
                        null, 240, List.of("目 次"), null, List.of("#/texts/17", "#/texts/18"), List.of(3), null),
                new DoclingChunk(6, "目 次\n附录 A ................ 10\n附录 B ................ 12",
                        null, 132, List.of("目 次"), null, List.of("#/texts/19", "#/texts/20"), List.of(3), null),
                new DoclingChunk(7, "前 言\n本标准按照 GB/T 1.1-2020 的规定起草。",
                        null, 320, List.of("前 言"), null, List.of("#/texts/21"), List.of(4), null)
        );
    }
}
