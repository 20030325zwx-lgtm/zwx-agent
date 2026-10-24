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

    public void triggerAsync(String tenantId, String agentKey, String documentId) {
        executor.submit(() -> {
            try {
                trigger(tenantId, agentKey, documentId);
            } catch (Exception exception) {
                log.warn("n8n resume trigger crashed unexpectedly; documentId={}, reason={}",
                        documentId, exception.getMessage());
            }
        });
    }

    void trigger(String tenantId, String agentKey, String documentId) {
        List<String> urls = jdbcTemplate.query("""
                SELECT resume_webhook_url FROM agent_knowledge_document
                WHERE id = ? AND resume_webhook_url IS NOT NULL
                """, (rs, rowNum) -> rs.getString(1), documentId);
        if (urls.isEmpty()) {
            return;
        }
        String url = urls.get(0);
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
                        .POST(HttpRequest.BodyPublishers.ofString("{\"phase\":\"finalize\"}"))
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
