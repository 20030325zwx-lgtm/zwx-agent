package com.zwx.zwxagent.knowledge.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 知识文档进入待审（READY + autoPublish=false）时，向 n8n 监听工作流推送事件，
 * 让审批过程在 n8n 执行历史里可视化（一条执行 = 一份文件 = 一个案件）。
 * 事件只是通知：失败仅告警日志，权威记录仍是 agent_knowledge_review_log 与 READY 列表。
 * 与 N8nResumeNotifier 同样强制 HTTP/1.1（n8n 对 h2c 升级请求会挂起，见 docs/bugs/）。
 */
@Component
public class N8nReviewEventNotifier {

    private static final Logger log = LoggerFactory.getLogger(N8nReviewEventNotifier.class);
    static final String EVENT_TYPE = "knowledge.review.pending";

    private final String webhookUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long[] retryBackoffMs;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "n8n-review-event-notifier");
        thread.setDaemon(true);
        return thread;
    });

    @org.springframework.beans.factory.annotation.Autowired
    public N8nReviewEventNotifier(@Value("${app.notify.n8n.review-webhook-url:}") String webhookUrl) {
        this(webhookUrl, new long[]{5000, 15000, 60000});
    }

    N8nReviewEventNotifier(String webhookUrl, long[] retryBackoffMs) {
        this.webhookUrl = webhookUrl == null || webhookUrl.isBlank() ? null : webhookUrl.trim();
        this.retryBackoffMs = retryBackoffMs;
    }

    /** 事件负载；字段是 n8n 执行详情里人眼识别案件的最低集合。 */
    record ReviewEventPayload(String eventId, String type, String documentId, String tenantId, String agentKey,
                              String filename, Integer versionNo, String submittedBy, String status, String occurredAt) {
    }

    boolean enabled() {
        return webhookUrl != null;
    }

    public void dispatchAsync(String documentId, String tenantId, String agentKey,
                              String filename, Integer versionNo, String submittedBy) {
        if (!enabled()) {
            log.debug("n8n review event webhook not configured, skip; documentId={}", documentId);
            return;
        }
        ReviewEventPayload payload = new ReviewEventPayload(UUID.randomUUID().toString(), EVENT_TYPE, documentId,
                tenantId, agentKey, filename, versionNo, submittedBy, "INDEXING", Instant.now().toString());
        executor.submit(() -> {
            try {
                dispatch(payload);
            } catch (Exception exception) {
                log.warn("n8n review event dispatch crashed unexpectedly; documentId={}, reason={}",
                        documentId, exception.getMessage());
            }
        });
    }

    void dispatch(ReviewEventPayload payload) {
        for (int attempt = 0; attempt <= retryBackoffMs.length; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(retryBackoffMs[attempt - 1]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    log.info("n8n review event dispatched; documentId={}, eventId={}", payload.documentId(), payload.eventId());
                    return;
                }
                log.warn("n8n review event webhook responded {}; documentId={}, attempt={}",
                        response.statusCode(), payload.documentId(), attempt + 1);
            } catch (Exception exception) {
                log.warn("n8n review event dispatch failed; documentId={}, attempt={}, reason={}",
                        payload.documentId(), attempt + 1, exception.getMessage());
            }
        }
        log.warn("n8n review event dispatch abandoned after retries; documentId={}——审批通知缺失，"
                + "待审清单以后端 GET /agent-knowledge/documents 为准", payload.documentId());
    }

    /** 供测试与调试校验负载结构。 */
    Map<String, Object> payloadMap(ReviewEventPayload payload) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventId", payload.eventId());
        map.put("type", payload.type());
        map.put("documentId", payload.documentId());
        map.put("tenantId", payload.tenantId());
        map.put("agentKey", payload.agentKey());
        map.put("filename", payload.filename());
        map.put("versionNo", payload.versionNo());
        map.put("submittedBy", payload.submittedBy());
        map.put("status", payload.status());
        map.put("occurredAt", payload.occurredAt());
        return map;
    }
}
