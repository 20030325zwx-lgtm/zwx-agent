package com.zwx.zwxagent.knowledge.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 审批落库后异步触发 n8n 人工审核 Wait 的恢复 URL（收尾通知）。
 * 恢复 URL 是恢复凭据：仅存于 agent_knowledge_document.resume_webhook_url，不写日志、不进响应。
 * 触发成功后清除；重试 3 次（5s/15s/60s）仍失败则记 RESUME_FAIL 审计，文档状态不受影响。
 */
@Component
public class N8nResumeNotifier {

    private static final Logger log = LoggerFactory.getLogger(N8nResumeNotifier.class);
    private static final long[] RETRY_BACKOFF_MS = {5000, 15000, 60000};

    private final JdbcTemplate jdbcTemplate;
    private final AgentKnowledgeReviewLogService reviewLogService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "n8n-resume-notifier");
        thread.setDaemon(true);
        return thread;
    });

    public N8nResumeNotifier(JdbcTemplate jdbcTemplate, AgentKnowledgeReviewLogService reviewLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.reviewLogService = reviewLogService;
    }

    public void triggerAsync(String documentId) {
        executor.submit(() -> {
            try {
                trigger(documentId);
            } catch (Exception exception) {
                log.warn("n8n resume trigger crashed unexpectedly; documentId={}, reason={}",
                        documentId, exception.getMessage());
            }
        });
    }

    void trigger(String documentId) {
        // 恢复 URL 由 n8n 案件在挂起前登记；小文档索引可能快过登记，因此查询随重试循环反复执行。
        String[] row = null;
        for (int attempt = 0; attempt <= RETRY_BACKOFF_MS.length && row == null; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(RETRY_BACKOFF_MS[attempt - 1]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            List<String[]> rows = jdbcTemplate.query("""
                    SELECT tenant_id, agent_key, resume_webhook_url, filename,
                           COALESCE(chunk_count, 0), COALESCE(parse_duration_ms, 0), COALESCE(vector_duration_ms, 0)
                    FROM agent_knowledge_document
                    WHERE id = ? AND resume_webhook_url IS NOT NULL
                    """, (rs, rowNum) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)}, documentId);
            row = rows.isEmpty() ? null : rows.get(0);
        }
        if (row == null) {
            log.warn("n8n resume abandoned: no resume URL registered after retries; documentId={}", documentId);
            return;
        }
        String tenantId = row[0];
        String agentKey = row[1];
        String url = row[2];
        String body;
        try {
            body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                    "phase", "finalize",
                    "documentId", documentId,
                    "filename", row[3] == null ? "" : row[3],
                    "chunks", Integer.parseInt(row[4]),
                    "parseMs", Integer.parseInt(row[5]),
                    "vectorMs", Integer.parseInt(row[6])));
        } catch (Exception exception) {
            body = "{\"phase\":\"finalize\"}";
        }
        for (int attempt = 0; attempt <= RETRY_BACKOFF_MS.length; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(RETRY_BACKOFF_MS[attempt - 1]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    jdbcTemplate.update(
                            "UPDATE agent_knowledge_document SET resume_webhook_url = NULL, resume_expires_at = NULL WHERE id = ?",
                            documentId);
                    return;
                }
                log.warn("n8n resume responded {}; documentId={}, attempt={}", response.statusCode(), documentId, attempt + 1);
            } catch (Exception exception) {
                log.warn("n8n resume trigger failed; documentId={}, attempt={}, reason={}",
                        documentId, attempt + 1, exception.getMessage());
            }
        }
        reviewLogService.record(tenantId, agentKey, documentId, "RESUME_FAIL", null, "SYSTEM",
                "n8n 恢复触发失败（3 次重试后放弃），审批状态已生效，请人工确认流程收尾", null);
    }
}
