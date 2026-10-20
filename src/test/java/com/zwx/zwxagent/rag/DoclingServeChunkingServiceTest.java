package com.zwx.zwxagent.rag;

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
        DoclingServeChunkingService service = new DoclingServeChunkingService(client, new DoclingDocumentPreprocessor(10, 1000), true, true);

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
        DoclingServeChunkingService service = new DoclingServeChunkingService(client, new DoclingDocumentPreprocessor(10, 1000), true, true);

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
                client, new DoclingDocumentPreprocessor(10, 1000), true, true);

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
}
