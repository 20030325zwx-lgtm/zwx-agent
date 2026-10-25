package com.zwx.zwxagent.platform.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DashScope qwen 兼容实现：用现有的 DashScope Key 做意图分流，免额外厂商依赖。
 * 与 JevIntentRouter 同一接口同一决策表，区别仅在模型：LLM 自报置信度（非校准）、延迟与成本更高，
 * 作为 Jev 不可用/未充值期间的对照与回退实现（见 docs/design/2026-10-25-jev-intent-routing.md §7）。
 * 任何异常返回 empty——分流失败绝不阻断主链路。
 */
@Slf4j
@Component
public class QwenIntentRouter implements IntentRouter {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final long timeoutMs;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public QwenIntentRouter(@Value("${app.intent.qwen.base-url:https://dashscope.aliyuncs.com}") String baseUrl,
                            @Value("${spring.ai.dashscope.api-key:}") String apiKey,
                            @Value("${app.intent.qwen.model:qwen-turbo}") String model,
                            @Value("${app.intent.qwen.timeout-ms:2500}") long timeoutMs) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
        this.model = model == null || model.isBlank() ? "qwen-turbo" : model.trim();
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Optional<IntentDecision> classify(String message) {
        if (apiKey == null || message == null || message.isBlank()) {
            return Optional.empty();
        }
        long startedAt = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/compatible-mode/v1/chat/completions"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(buildRequest(message))))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = System.currentTimeMillis() - startedAt;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("qwen intent classify responded {}; latencyMs={}", response.statusCode(), latencyMs);
                return Optional.empty();
            }
            Optional<IntentDecision> decision = Optional.of(parse(response.body(), latencyMs));
            decision.ifPresent(value -> log.info(
                    "qwen intent classified (自报置信，非校准): intent={}, confidence={}, complexity={}, needsKnowledge={}, latencyMs={}",
                    value.intent(), value.intentConfidence(), value.complexity(), value.needsKnowledge(), value.latencyMs()));
            return decision;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception exception) {
            log.warn("qwen intent classify failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    Map<String, Object> buildRequest(String message) {
        String systemPrompt = """
                你是意图识别器。对用户消息做判断后，只输出一个 JSON 对象，禁止输出任何其他文字或代码块标记。字段：
                intent：取值只能是 知识库问答、联网查询、工具任务、闲聊问答 之一；
                confidence：0 到 1 的数字，你对意图判断的把握；
                complexity：0 到 2 的数字（0=单轮可答，1=需要多步骤，2=需要研究分析）；
                needs_knowledge：0 到 1 的数字，回答该问题需要检索私有知识库的概率。
                """;
        return Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", message)),
                "temperature", 0.1,
                "max_tokens", 120);
    }

    /** LLM 输出可能带代码块标记或多余文字，取第一个 { 到最后一个 } 之间的片段解析。 */
    IntentDecision parse(String responseBody, long latencyMs) throws Exception {
        JsonNode content = objectMapper.readTree(responseBody)
                .path("choices").path(0).path("message").path("content");
        String text = content.asText("");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("qwen intent response has no JSON object: " + text);
        }
        JsonNode node = objectMapper.readTree(text.substring(start, end + 1));
        return new IntentDecision(
                node.path("intent").asText(""),
                node.path("confidence").asDouble(0),
                node.path("complexity").asDouble(0),
                0,
                node.path("needs_knowledge").asDouble(0.5),
                latencyMs);
    }
}
