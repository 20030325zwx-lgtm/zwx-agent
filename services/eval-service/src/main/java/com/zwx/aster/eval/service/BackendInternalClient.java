package com.zwx.aster.eval.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主站内部 API 客户端：意图分类 / 检索校验 / TASK 无头执行。
 * 统一携带 X-Aster-Internal-Token（与主站 PLATFORM_INTERNAL_TOKEN 一致）。
 */
@Component
public class BackendInternalClient {

    public static final String INTERNAL_HEADER = "X-Aster-Internal-Token";

    private final RestClient restClient;
    private final String internalToken;

    public BackendInternalClient(@Value("${app.internal.backend-base-url:http://backend:8123}") String baseUrl,
                                 @Value("${app.internal.token:}") String internalToken) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.internalToken = internalToken;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> intent(String question) {
        return post("/api/internal/eval/intent", Map.of("question", question));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyRetrieval(String tenantId, String agentKey, String documentId, String query) {
        return post("/api/internal/eval/verify-retrieval", Map.of(
                "tenantId", tenantId, "agentKey", agentKey, "documentId", documentId, "query", query));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> taskRun(String tenantId, String task, boolean webSearch, boolean knowledge,
                                       int timeoutMinutes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("task", task);
        body.put("webSearch", webSearch);
        body.put("knowledge", knowledge);
        body.put("timeoutMinutes", timeoutMinutes);
        return post("/api/internal/eval/task-run", body);
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        try {
            Map<String, Object> response = restClient.post().uri(path)
                    .header(INTERNAL_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return response == null ? Map.of() : response;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("主站内部接口调用失败（" + path + "）：" + exception.getMessage(), exception);
        }
    }
}
