package com.zwx.aster.data.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 主站内部 API 客户端（X-Aster-Internal-Token）：只读轨迹，供「从 trace 建用例」。
 * 内部令牌是租户级能力，调用方必须显式传 tenantId，主站侧按租户校验。
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
    public List<Map<String, Object>> recentRuns(String tenantId, int limit) {
        List<Map<String, Object>> response = restClient.get()
                .uri(builder -> builder.path("/api/internal/eval/runs")
                        .queryParam("tenantId", tenantId)
                        .queryParam("limit", limit)
                        .build())
                .header(INTERNAL_HEADER, internalToken)
                .retrieve()
                .body(List.class);
        return response == null ? List.of() : response;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> trajectory(String tenantId, String runId) {
        Map<String, Object> response = restClient.get()
                .uri(builder -> builder.path("/api/internal/eval/runs/{runId}/trajectory")
                        .queryParam("tenantId", tenantId)
                        .build(runId))
                .header(INTERNAL_HEADER, internalToken)
                .retrieve()
                .body(Map.class);
        return response == null ? Map.of() : response;
    }
}
