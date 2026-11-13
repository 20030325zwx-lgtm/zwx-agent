package com.zwx.zwxagent.knowledge.document;

import com.zwx.zwxagent.platform.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import com.zwx.zwxagent.knowledge.vector.KnowledgeVectorStore;
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
        KnowledgeVectorStore vectorStore = mock(KnowledgeVectorStore.class);
        when(jdbcTemplate.queryForList(contains("SELECT object_key"), eq(String.class), eq("doc-1")))
                .thenReturn(List.of("knowledge/default/super/doc-1-file.pdf"));
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

        // 2026-11-13 迁移 Weaviate：按文档删除由端口方法完成（where 过滤批量删），不再先 SQL 收集 chunkIds
        verify(vectorStore).deleteByDocumentId("doc-1");
        verify(jdbcTemplate).update(contains("DELETE FROM agent_knowledge_block"), eq("doc-1"));
        verify(jdbcTemplate).update(contains("DELETE FROM agent_knowledge_document"), eq("doc-1"));
        verify(storage).delete("knowledge/default/super/doc-1-file.pdf");
        verify(storage).delete("knowledge/default/super/doc-1/mineru/full.md");
    }
}
