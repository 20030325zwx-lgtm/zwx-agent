package com.zwx.aster.data.service;

import com.zwx.aster.data.security.JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轨迹浏览与回流：从一条线上 run 直接生成回归用例，免去复制粘贴。
 * 轨迹只读走主站内部接口；建用例复用评测中台的 backflow。
 */
@Service
public class RunTraceService {

    private static final String INTERNAL_HEADER = "X-Aster-Internal-Token";

    private final BackendInternalClient backendClient;
    private final RestClient evalClient;
    private final String internalToken;

    public RunTraceService(BackendInternalClient backendClient,
                           @Value("${app.internal.eval-base-url:http://eval-service:8140}") String evalBaseUrl,
                           @Value("${app.internal.token:}") String internalToken) {
        this.backendClient = backendClient;
        this.evalClient = RestClient.builder().baseUrl(evalBaseUrl).build();
        this.internalToken = internalToken;
    }

    public List<Map<String, Object>> recentRuns(JwtVerifier.Actor actor, int limit) {
        return backendClient.recentRuns(actor.tenantId(), Math.min(Math.max(limit, 1), 100));
    }

    public Map<String, Object> trajectory(JwtVerifier.Actor actor, String runId) {
        return backendClient.trajectory(actor.tenantId(), runId);
    }

    /** 从 run 建回归用例：question/answer 取自轨迹快照，结论与工具轨迹摘要进 notes。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toCase(JwtVerifier.Actor actor, String runId, String datasetId, List<String> tags) {
        Map<String, Object> trajectory = backendClient.trajectory(actor.tenantId(), runId);
        if (trajectory.isEmpty()) {
            throw new IllegalArgumentException("run 不存在或无权访问：" + runId);
        }
        Map<String, Object> run = trajectory.get("run") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        Map<String, Object> checkpoint = trajectory.get("checkpoint") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        String question = text(run.get("input"));
        String answer = text(checkpoint.get("finalAnswer"));
        if (answer == null) answer = text(checkpoint.get("partialAnswer"));
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("该 run 没有输入，无法建用例");
        }
        int toolCount = trajectory.get("toolCalls") instanceof List<?> tools ? tools.size() : 0;
        int llmCalls = trajectory.get("llmCalls") instanceof List<?> calls ? calls.size() : 0;
        String reason = "[trace 回流] run=" + runId.substring(0, Math.min(8, runId.length()))
                + " 工具 " + toolCount + " 次 / 模型 " + llmCalls + " 次，人工判定需要回归";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", actor.tenantId());
        body.put("feedbackId", runId);
        body.put("rating", "down");
        body.put("reason", reason);
        body.put("question", question);
        body.put("answer", answer == null ? "" : answer);
        if (datasetId != null && !datasetId.isBlank()) body.put("datasetId", datasetId);
        body.put("tags", tags == null || tags.isEmpty() ? List.of("trace") : tags);
        try {
            Map<String, Object> response = evalClient.post().uri("/api/internal/eval/backflow")
                    .header(INTERNAL_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return response == null ? Map.of() : response;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("建用例失败（评测中台不可用）：" + exception.getMessage(), exception);
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
