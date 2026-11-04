package com.zwx.aster.eval.service;

import com.zwx.aster.eval.domain.EvalJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** DashScope 兼容模式直连（PROMPT 评测与 LLM-as-judge），不依赖主站模型目录。 */
@Component
public class DashScopeClient {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public DashScopeClient(@Value("${app.dashscope.base-url:https://dashscope.aliyuncs.com}") String baseUrl,
                           @Value("${app.dashscope.api-key:}") String apiKey,
                           @Value("${app.dashscope.model:qwen-plus}") String model,
                           @Value("${app.dashscope.timeout-seconds:120}") long timeoutSeconds) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
        this.model = model;
    }

    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String chat(String system, String user) {
        if (!available()) {
            throw new IllegalStateException("评测模型未配置：DASHSCOPE_API_KEY 为空");
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.1,
                "messages", system == null || system.isBlank()
                        ? List.of(Map.of("role", "user", "content", user))
                        : List.of(Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)));
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri("/compatible-mode/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response == null) return "";
        var choices = EvalJson.parse(EvalJson.toJson(response.get("choices")));
        if (!choices.isArray() || choices.isEmpty()) return "";
        var message = choices.get(0).get("message");
        return message == null || message.get("content") == null ? "" : message.get("content").asText();
    }

    public String model() {
        return model;
    }
}
