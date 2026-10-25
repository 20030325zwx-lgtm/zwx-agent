package com.zwx.zwxagent.knowledge.document;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 大文档向量写入：批大小 25 保持不变，批间并行不得丢失或重排任何切片。 */
class AgentKnowledgeDocumentVectorBatchTest {

    private AgentKnowledgeDocumentService service(VectorStore vectorStore) {
        AgentKnowledgeDocumentService documentService = new AgentKnowledgeDocumentService(
                mock(com.zwx.zwxagent.knowledge.storage.OssClientProvider.class), "zwx-agent",
                mock(org.springframework.jdbc.core.JdbcTemplate.class), vectorStore, new MyTokenTextSplitter(),
                mock(DocumentParsingModule.class), mock(DoclingServeChunkingService.class), mock(MineruDocumentParser.class),
                mock(MineruBlockParser.class), mock(StructuredKnowledgeChunker.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), "mineru",
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                mock(AgentKnowledgeReviewLogService.class), mock(N8nResumeNotifier.class),
                mock(N8nReviewEventNotifier.class));
        return documentService;
    }

    private List<Document> chunks(int count) {
        List<Document> chunks = new ArrayList<>();
        for (int i = 0; i < count; i++) chunks.add(new Document("chunk-" + i));
        return chunks;
    }

    @Test
    void largeDocumentSplitsIntoBatchesAndStoresEveryChunk() {
        VectorStore vectorStore = mock(VectorStore.class);
        AgentKnowledgeDocumentService documentService = service(vectorStore);
        documentService.embeddingParallelism = 4;

        documentService.storeVectors(chunks(60));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(3)).add(captor.capture());
        List<List<Document>> batches = captor.getAllValues();
        // 并行下批次完成顺序不定，只断言批次大小集合
        assertThat(batches).hasSize(3);
        assertThat(batches.stream().mapToInt(List::size).sorted()).containsExactly(10, 25, 25);
        List<String> stored = batches.stream().flatMap(List::stream).map(Document::getText).sorted().toList();
        assertThat(stored).hasSize(60);
        for (int i = 0; i < 60; i++) assertThat(stored).contains("chunk-" + i);
    }

    @Test
    void smallDocumentUsesSingleBatch() {
        VectorStore vectorStore = mock(VectorStore.class);

        service(vectorStore).storeVectors(chunks(10));

        verify(vectorStore, times(1)).add(anyList());
        verify(vectorStore, never()).delete(anyList());
    }
}
