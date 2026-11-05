package com.zwx.zwxagent.knowledge.document;

import com.zwx.zwxagent.platform.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentKnowledgeDocumentServiceUploadTest {

    @Test
    void uploadsKnowledgeToOssWithoutDeletingExistingVersions() {
        ObjectStorage storage = mock(ObjectStorage.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(contains("lifecycle_status = 'ACTIVE'"), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(version_no)"), eq(Integer.class), any(), any(), any()))
                .thenReturn(3);
        when(jdbcTemplate.queryForObject(contains("FROM agent_knowledge_category"), eq(Integer.class), any(), any(), any()))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("SELECT id FROM agent_knowledge_category"), eq(String.class), any(), any(), any()))
                .thenReturn("default-category");
        when(jdbcTemplate.queryForObject(contains("SELECT id, tenant_id"), any(RowMapper.class), any(), any(), any()))
                .thenReturn(new AgentKnowledgeDocument("document-id", "default", "love", "sample.md", "PENDING", 0, null,
                        Instant.now(), null, "sample", 3, "INDEXING", null, null, null, "admin"));

        AgentKnowledgeDocumentService service = new AgentKnowledgeDocumentService(
                storage, jdbcTemplate, mock(VectorStore.class), new MyTokenTextSplitter(),
                mock(DocumentParsingModule.class), mock(DoclingServeChunkingService.class), mock(MineruDocumentParser.class),
                mock(MineruBlockParser.class), mock(StructuredKnowledgeChunker.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                "mineru", mock(PlatformTransactionManager.class), mock(AgentKnowledgeReviewLogService.class),
                mock(N8nResumeNotifier.class), mock(N8nReviewEventNotifier.class));

        service.upload("default", "love", new MockMultipartFile("file", "sample.md", "text/markdown", "# Test\nKnowledge content".getBytes()), "admin");

        verify(storage).putBytes(
                org.mockito.ArgumentMatchers.argThat(key -> key != null && key.startsWith("knowledge/default/love/")),
                eq("text/plain; charset=UTF-8"), any(byte[].class));
        verify(jdbcTemplate).update(contains("INSERT INTO agent_knowledge_document"), any(Object[].class));
        verify(jdbcTemplate).update(contains("logical_key"), any(Object[].class));
        verify(jdbcTemplate).update(contains("version_no"), any(Object[].class));
        verify(jdbcTemplate).update(contains("'INDEXING'"), any(Object[].class));
        verify(storage, never()).delete(any(String.class));
        verify(jdbcTemplate, never()).update(contains("DELETE FROM agent_knowledge_document"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("DELETE FROM agent_knowledge_document"), any(), any(), any(), any(), anyInt());
    }
}
