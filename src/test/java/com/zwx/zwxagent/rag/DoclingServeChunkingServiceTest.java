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
        DoclingServeChunkingService service = new DoclingServeChunkingService(client, true);

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
        DoclingServeChunkingService service = new DoclingServeChunkingService(client, true);

        Assertions.assertThrows(DoclingServeException.class,
                () -> service.chunk(new byte[]{1}, "empty.pdf", Map.of()));
    }
}
