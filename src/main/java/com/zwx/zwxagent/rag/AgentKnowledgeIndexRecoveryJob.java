package com.zwx.zwxagent.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Retries transient indexing failures without blocking the upload request thread. */
@Component
class AgentKnowledgeIndexRecoveryJob {
    private final JdbcTemplate jdbcTemplate;
    private final AgentKnowledgeDocumentService documentService;

    AgentKnowledgeIndexRecoveryJob(JdbcTemplate jdbcTemplate, AgentKnowledgeDocumentService documentService) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentService = documentService;
    }

    @Scheduled(fixedDelayString = "${app.agent.rag.index-retry-interval-ms:60000}")
    void retryFailedIndexes() {
        List<String> ids = jdbcTemplate.queryForList("""
                SELECT id FROM agent_knowledge_document
                WHERE lifecycle_status = 'FAILED' AND next_retry_at <= CURRENT_TIMESTAMP AND index_attempts < 3
                ORDER BY next_retry_at ASC LIMIT 10
                """, String.class);
        for (String id : ids) {
            int updated = jdbcTemplate.update("""
                    UPDATE agent_knowledge_document SET lifecycle_status = 'INDEXING', status = 'PENDING', next_retry_at = NULL
                    WHERE id = ? AND lifecycle_status = 'FAILED' AND index_attempts < 3
                    """, id);
            if (updated == 1) documentService.indexDocument(id);
        }
    }
}
