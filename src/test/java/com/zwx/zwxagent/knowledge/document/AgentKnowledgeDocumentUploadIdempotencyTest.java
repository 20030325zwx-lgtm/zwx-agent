package com.zwx.zwxagent.knowledge.document;

import com.zwx.zwxagent.platform.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import com.zwx.zwxagent.knowledge.vector.KnowledgeVectorStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentKnowledgeDocumentUploadIdempotencyTest {

    private AgentKnowledgeDocumentService service(ObjectStorage storage, JdbcTemplate jdbcTemplate) {
        return new AgentKnowledgeDocumentService(
                storage, jdbcTemplate, mock(KnowledgeVectorStore.class), new MyTokenTextSplitter(),
                mock(DocumentParsingModule.class), mock(DoclingServeChunkingService.class), mock(MineruDocumentParser.class),
                mock(MineruBlockParser.class), mock(StructuredKnowledgeChunker.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), "mineru", mock(PlatformTransactionManager.class),
                mock(AgentKnowledgeReviewLogService.class), mock(N8nResumeNotifier.class),
                mock(N8nReviewEventNotifier.class));
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "sample.md", "text/markdown", "# Test\nKnowledge content".getBytes());
    }

    private AgentKnowledgeDocument existingDocument() {
        return new AgentKnowledgeDocument("existing-id", "default", "test", "sample.md", "READY", 1, null,
                Instant.now(), null, "sample", 2, "READY", null, null, null, "admin");
    }

    @Test
    void retryWithSameIdempotencyKeyReturnsExistingDocumentWithoutTouchingOss() {
        ObjectStorage storage = mock(ObjectStorage.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(contains("idempotency_key = ?"), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.of("existing-id"));
        when(jdbcTemplate.queryForObject(contains("WHERE id = ? AND tenant_id = ? AND agent_key = ?"),
                any(RowMapper.class), any(), any(), any()))
                .thenReturn(existingDocument());

        AgentKnowledgeDocument result = service(storage, jdbcTemplate)
                .upload("default", "test", file(), "admin", null, false, "n8n-exec-1");

        assertThat(result.id()).isEqualTo("existing-id");
        verify(storage, never()).putBytes(any(String.class), any(String.class), any(byte[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO agent_knowledge_document"), any(Object[].class));
    }

    @Test
    void insertRaceOnIdempotencyKeyReturnsWinnerDocument() {
        ObjectStorage storage = mock(ObjectStorage.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(contains("idempotency_key = ?"), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.of())                              // 首查未命中
                .thenReturn(List.of("winner-id"));                  // 冲突后重查命中
        when(jdbcTemplate.query(contains("lifecycle_status = 'ACTIVE'"), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(version_no)"), eq(Integer.class), any(), any(), any()))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("FROM agent_knowledge_category"), eq(Integer.class), any(), any(), any()))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("SELECT id FROM agent_knowledge_category"), eq(String.class), any(), any(), any()))
                .thenReturn("default-category");
        when(jdbcTemplate.update(contains("INSERT INTO agent_knowledge_document"), any(Object[].class)))
                .thenThrow(new DataIntegrityViolationException("uk_agent_knowledge_document_idem"));
        when(jdbcTemplate.queryForObject(contains("WHERE id = ? AND tenant_id = ? AND agent_key = ?"),
                any(RowMapper.class), any(), any(), any()))
                .thenReturn(new AgentKnowledgeDocument("winner-id", "default", "test", "sample.md", "READY", 1, null,
                        Instant.now(), null, "sample", 2, "READY", null, null, null, "admin"));

        AgentKnowledgeDocument result = service(storage, jdbcTemplate)
                .upload("default", "test", file(), "admin", null, false, "n8n-exec-1");

        assertThat(result.id()).isEqualTo("winner-id");
    }

    @Test
    void blankIdempotencyKeyBehavesLikeNoKey() {
        ObjectStorage storage = mock(ObjectStorage.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(contains("lifecycle_status = 'ACTIVE'"), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(contains("COALESCE(MAX(version_no)"), eq(Integer.class), any(), any(), any()))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("FROM agent_knowledge_category"), eq(Integer.class), any(), any(), any()))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("SELECT id FROM agent_knowledge_category"), eq(String.class), any(), any(), any()))
                .thenReturn("default-category");
        when(jdbcTemplate.queryForObject(contains("SELECT id, tenant_id"), any(RowMapper.class), any(), any(), any()))
                .thenReturn(new AgentKnowledgeDocument("new-id", "default", "test", "sample.md", "PENDING", 0, null,
                        Instant.now(), null, "sample", 1, "INDEXING", null, null, null, "admin"));

        service(storage, jdbcTemplate).upload("default", "test", file(), "admin", null, false, "  ");

        verify(jdbcTemplate, never()).query(contains("idempotency_key = ?"), any(RowMapper.class), any(), any(), any());
        verify(jdbcTemplate).update(contains("INSERT INTO agent_knowledge_document"), any(Object[].class));
    }
}
