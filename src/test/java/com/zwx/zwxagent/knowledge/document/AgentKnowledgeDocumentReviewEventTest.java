package com.zwx.zwxagent.knowledge.document;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 待审提交出口（notifyPendingReview）：SUBMIT 审计必须记录；
 * 案件唤醒（resume）只对非 n8n 来源（幂等键不以 n8n- 开头）的文档触发，
 * 避免 resume 打进 n8n ingestion 工作流自己登记的审批 Wait。
 */
class AgentKnowledgeDocumentReviewEventTest {

    private AgentKnowledgeDocumentService service(JdbcTemplate jdbcTemplate,
                                                  AgentKnowledgeReviewLogService reviewLogService,
                                                  N8nResumeNotifier resumeNotifier) {
        return new AgentKnowledgeDocumentService(
                mock(com.zwx.zwxagent.knowledge.storage.OssClientProvider.class), "zwx-agent", jdbcTemplate,
                mock(org.springframework.ai.vectorstore.VectorStore.class), new MyTokenTextSplitter(),
                mock(DocumentParsingModule.class), mock(DoclingServeChunkingService.class), mock(MineruDocumentParser.class),
                mock(MineruBlockParser.class), mock(StructuredKnowledgeChunker.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), "mineru",
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                reviewLogService, resumeNotifier, mock(N8nReviewEventNotifier.class));
    }

    private JdbcTemplate scopeReturning(JdbcTemplate jdbcTemplate, String idempotencyKey) {
        when(jdbcTemplate.queryForObject(contains("COALESCE(idempotency_key"), any(RowMapper.class), eq("doc-1")))
                .thenReturn(new String[]{"default", "super", idempotencyKey});
        return jdbcTemplate;
    }

    @Test
    void manualUploadRecordsSubmitAuditAndResumesCase() {
        JdbcTemplate jdbcTemplate = scopeReturning(mock(JdbcTemplate.class), "");
        AgentKnowledgeReviewLogService reviewLogService = mock(AgentKnowledgeReviewLogService.class);
        N8nResumeNotifier resumeNotifier = mock(N8nResumeNotifier.class);

        service(jdbcTemplate, reviewLogService, resumeNotifier).notifyPendingReview("doc-1");

        verify(reviewLogService).record(eq("default"), eq("super"), eq("doc-1"), eq("SUBMIT"), eq(null),
                eq("SYSTEM"), eq("索引完成，等待人工审核"), eq(null));
        verify(resumeNotifier).triggerAsync("doc-1");
    }

    @Test
    void n8nOriginDocumentDoesNotGetPhaseResume() {
        JdbcTemplate jdbcTemplate = scopeReturning(mock(JdbcTemplate.class), "n8n-42");
        AgentKnowledgeReviewLogService reviewLogService = mock(AgentKnowledgeReviewLogService.class);
        N8nResumeNotifier resumeNotifier = mock(N8nResumeNotifier.class);

        service(jdbcTemplate, reviewLogService, resumeNotifier).notifyPendingReview("doc-1");

        verify(reviewLogService).record(eq("default"), eq("super"), eq("doc-1"), eq("SUBMIT"), any(), any(), any(), any());
        verify(resumeNotifier, never()).triggerAsync(any());
    }
}
