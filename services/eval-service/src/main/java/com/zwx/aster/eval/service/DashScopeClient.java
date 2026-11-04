package com.zwx.aster.eval.service;

import com.zwx.aster.eval.domain.EvalJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DashScope 兼容模式直连（PROMPT 评测与 LLM-as-judge），不依赖主站模型目录。 */
@Component
public class DashScopeClient {

    /** 一次补全：正文 + 真实 token 用量（供应商不返回时为 null）。 */
    public record Completion(String text, Integer inputTokens, Integer outputTokens) {
    }

    private final RestClient restClient;
    private final String apiKey;
    private final String defaultModel;

    public DashScopeClient(@Value("${app.dashscope.base-url:https://dashscope.aliyuncs.com}") String baseUrl,
                           @Value("${app.dashscope.api-key:}") String apiKey,
                           @Value("${app.dashscope.model:qwen-plus}") String defaultModel,
                           @Value("${app.dashscope.timeout-seconds:120}") long timeoutSeconds) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
    }

    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String model() {
        return defaultModel;
    }

    public String chat(String system, String user) {
        return chat(system, user, defaultModel, null).text();
    }

    /** 指定模型与温度补全；温度为空时用 0.1（评测要可复现，不追求发散）。 */
    @SuppressWarnings("unchecked")
    public Completion chat(String system, String user, String model, Double temperature) {
        if (!available()) {
            throw new IllegalStateException("评测模型未配置：DASHSCOPE_API_KEY 为空");
        }
        String chosenModel = model == null || model.isBlank() ? defaultModel : model;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", chosenModel);
        body.put("temperature", temperature == null ? 0.1 : temperature);
        body.put("messages", system == null || system.isBlank()
                ? List.of(Map.of("role", "user", "content", user))
                : List.of(Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        Map<String, Object> response = restClient.post()
                .uri("/compatible-mode/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response == null) return new Completion("", null, null);
        var choices = EvalJson.parse(EvalJson.toJson(response.get("choices")));
        String text = "";
        if (choices.isArray() && !choices.isEmpty()) {
            var message = choices.get(0).get("message");
            if (message != null && message.get("content") != null) text = message.get("content").asText();
        }
        Integer inputTokens = null;
        Integer outputTokens = null;
        if (response.get("usage") instanceof Map<?, ?> usage) {
            inputTokens = intOf(usage.get("prompt_tokens"), usage.get("input_tokens"));
            outputTokens = intOf(usage.get("completion_tokens"), usage.get("output_tokens"));
        }
        return new Completion(text, inputTokens, outputTokens);
    }

    private static Integer intOf(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate instanceof Number n) return n.intValue();
        }
        return null;
    }
}
