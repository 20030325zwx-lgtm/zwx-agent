package com.zwx.zwxagent.knowledge.document;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 待审提交出口（notifyPendingReview）：SUBMIT 审计必须记录；
 * n8n 事件只对非 n8n 来源（幂等键不以 n8n- 开头）的文档推送，避免 n8n ingestion 文档重复开监听执行。
 */
class AgentKnowledgeDocumentReviewEventTest {

    private AgentKnowledgeDocumentService service(JdbcTemplate jdbcTemplate,
                                                  AgentKnowledgeReviewLogService reviewLogService,
                                                  N8nReviewEventNotifier eventNotifier) {
        return new AgentKnowledgeDocumentService(
                mock(com.zwx.zwxagent.knowledge.storage.OssClientProvider.class), "zwx-agent", jdbcTemplate,
                mock(org.springframework.ai.vectorstore.VectorStore.class), new MyTokenTextSplitter(),
                mock(DocumentParsingModule.class), mock(DoclingServeChunkingService.class), mock(MineruDocumentParser.class),
                mock(MineruBlockParser.class), mock(StructuredKnowledgeChunker.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), "mineru",
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                reviewLogService, mock(N8nResumeNotifier.class), eventNotifier);
    }

    private JdbcTemplate scopeReturning(JdbcTemplate jdbcTemplate, String idempotencyKey) {
        when(jdbcTemplate.queryForObject(contains("COALESCE(idempotency_key"), any(RowMapper.class), eq("doc-1")))
                .thenReturn(new String[]{"default", "super", idempotencyKey, "员工手册v3.pdf", "3", "admin"});
        return jdbcTemplate;
    }

    @Test
    void manualUploadRecordsSubmitAuditAndDispatchesReviewEvent() {
        JdbcTemplate jdbcTemplate = scopeReturning(mock(JdbcTemplate.class), "");
        AgentKnowledgeReviewLogService reviewLogService = mock(AgentKnowledgeReviewLogService.class);
        N8nReviewEventNotifier eventNotifier = mock(N8nReviewEventNotifier.class);

        service(jdbcTemplate, reviewLogService, eventNotifier).notifyPendingReview("doc-1");

        verify(reviewLogService).record(eq("default"), eq("super"), eq("doc-1"), eq("SUBMIT"), eq(null),
                eq("SYSTEM"), eq("索引完成，等待人工审核"), eq(null));
        ArgumentCaptor<String> documentId = ArgumentCaptor.forClass(String.class);
        verify(eventNotifier).dispatchAsync(documentId.capture(), eq("default"), eq("super"),
                eq("员工手册v3.pdf"), eq(3), eq("admin"));
        assertThat(documentId.getValue()).isEqualTo("doc-1");
    }

    @Test
    void n8nOriginDocumentDoesNotSpawnSecondWatcherEvent() {
        JdbcTemplate jdbcTemplate = scopeReturning(mock(JdbcTemplate.class), "n8n-42");
        AgentKnowledgeReviewLogService reviewLogService = mock(AgentKnowledgeReviewLogService.class);
        N8nReviewEventNotifier eventNotifier = mock(N8nReviewEventNotifier.class);

        service(jdbcTemplate, reviewLogService, eventNotifier).notifyPendingReview("doc-1");

        verify(reviewLogService).record(eq("default"), eq("super"), eq("doc-1"), eq("SUBMIT"), any(), any(), any(), any());
        verify(eventNotifier, never()).dispatchAsync(any(), any(), any(), any(), any(), any());
    }
}
