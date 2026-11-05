package com.zwx.zwxagent.knowledge.document;

import com.zwx.zwxagent.platform.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentKnowledgeDocumentDeleteTest {

    @Test
    void removesVectorsBlocksDocumentAndMineruObjects() {
        ObjectStorage storage = mock(ObjectStorage.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        VectorStore vectorStore = mock(VectorStore.class);
        when(jdbcTemplate.queryForList(contains("SELECT object_key"), eq(String.class), eq("doc-1")))
                .thenReturn(List.of("knowledge/default/super/doc-1-file.pdf"));
        when(jdbcTemplate.queryForList(contains("FROM agent_knowledge_vector"), eq(String.class), eq("doc-1")))
                .thenReturn(List.of("chunk-1"));
        when(storage.listKeys("knowledge/default/super/doc-1/"))
                .thenReturn(List.of("knowledge/default/super/doc-1/mineru/full.md"));

        AgentKnowledgeDocumentService service = new AgentKnowledgeDocumentService(
                storage, jdbcTemplate, vectorStore, new MyTokenTextSplitter(),
                mock(DocumentParsingModule.class), mock(DoclingServeChunkingService.class), mock(MineruDocumentParser.class),
                mock(MineruBlockParser.class), mock(StructuredKnowledgeChunker.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), "mineru", mock(PlatformTransactionManager.class),
                mock(AgentKnowledgeReviewLogService.class), mock(N8nResumeNotifier.class),
                mock(N8nReviewEventNotifier.class));

        service.removeDocumentCompletely("doc-1", "default", "super");

        verify(vectorStore).delete(List.of("chunk-1"));
        verify(jdbcTemplate).update(contains("DELETE FROM agent_knowledge_block"), eq("doc-1"));
        verify(jdbcTemplate).update(contains("DELETE FROM agent_knowledge_document"), eq("doc-1"));
        verify(storage).delete("knowledge/default/super/doc-1-file.pdf");
        verify(storage).delete("knowledge/default/super/doc-1/mineru/full.md");
    }
}
