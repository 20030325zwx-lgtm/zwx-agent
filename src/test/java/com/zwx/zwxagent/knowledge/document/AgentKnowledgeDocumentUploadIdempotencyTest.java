package com.zwx.zwxagent.knowledge.document;

import com.aliyun.oss.OSS;
import com.zwx.zwxagent.knowledge.storage.OssClientProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
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

    private AgentKnowledgeDocumentService service(OssClientProvider ossClientProvider, JdbcTemplate jdbcTemplate) {
        return new AgentKnowledgeDocumentService(
                ossClientProvider, "zwx-agent", jdbcTemplate, mock(VectorStore.class), new MyTokenTextSplitter(),
                mock(DocumentParsingModule.class), mock(DoclingServeChunkingService.class), mock(MineruDocumentParser.class),
                mock(MineruBlockParser.class), mock(StructuredKnowledgeChunker.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), "mineru", mock(PlatformTransactionManager.class),
                mock(AgentKnowledgeReviewLogService.class), mock(N8nResumeNotifier.class));
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
        OssClientProvider ossClientProvider = mock(OssClientProvider.class);
        OSS oss = mock(OSS.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(contains("idempotency_key = ?"), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.of("existing-id"));
        when(jdbcTemplate.queryForObject(contains("WHERE id = ? AND tenant_id = ? AND agent_key = ?"),
                any(RowMapper.class), any(), any(), any()))
                .thenReturn(existingDocument());

        AgentKnowledgeDocument result = service(ossClientProvider, jdbcTemplate)
                .upload("default", "test", file(), "admin", null, false, "n8n-exec-1");

        assertThat(result.id()).isEqualTo("existing-id");
        verify(oss, never()).putObject(any(String.class), any(String.class), any(java.io.InputStream.class), any());
        verify(jdbcTemplate, never()).update(contains("INSERT INTO agent_knowledge_document"), any(Object[].class));
    }

    @Test
    void insertRaceOnIdempotencyKeyReturnsWinnerDocument() {
        OssClientProvider ossClientProvider = mock(OssClientProvider.class);
        OSS oss = mock(OSS.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(ossClientProvider.getClient()).thenReturn(oss);
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

        AgentKnowledgeDocument result = service(ossClientProvider, jdbcTemplate)
                .upload("default", "test", file(), "admin", null, false, "n8n-exec-1");

        assertThat(result.id()).isEqualTo("winner-id");
    }

    @Test
    void blankIdempotencyKeyBehavesLikeNoKey() {
        OssClientProvider ossClientProvider = mock(OssClientProvider.class);
        OSS oss = mock(OSS.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(ossClientProvider.getClient()).thenReturn(oss);
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

        service(ossClientProvider, jdbcTemplate).upload("default", "test", file(), "admin", null, false, "  ");

        verify(jdbcTemplate, never()).query(contains("idempotency_key = ?"), any(RowMapper.class), any(), any(), any());
        verify(jdbcTemplate).update(contains("INSERT INTO agent_knowledge_document"), any(Object[].class));
    }
}
