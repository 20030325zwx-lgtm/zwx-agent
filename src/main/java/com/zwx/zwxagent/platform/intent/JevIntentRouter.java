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
 * TypeSafe Jev（System One 决策模型）意图分流器。
 * 一次请求两问并行：意图 choice + 复杂度 score。
 * 意图只区分工作任务/闲聊问答并产出闲聊短路候选；检索开关与是否联网均不由意图改写。
 * 任何异常返回 empty——分流失败绝不阻断主链路。
 */
@Slf4j
@Component
public class JevIntentRouter implements IntentRouter {

    private static final String INTENT_QUESTION = "intent";
    private static final String COMPLEXITY_QUESTION = "complexity";

    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final long timeoutMs;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();

    public JevIntentRouter(@Value("${app.intent.jev.enabled:true}") boolean enabled,
                           @Value("${app.intent.jev.base-url:https://api.typesafe.ai}") String baseUrl,
                           @Value("${app.intent.jev.api-key:}") String apiKey,
                           @Value("${app.intent.jev.model:jev-latest}") String model,
                           @Value("${app.intent.jev.timeout-ms:1500}") long timeoutMs) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
        this.model = model == null || model.isBlank() ? "jev-latest" : model.trim();
        this.timeoutMs = timeoutMs;
    }

    @Override
    public Optional<IntentDecision> classify(String message) {
        if (!enabled || apiKey == null || message == null || message.isBlank()) {
            return Optional.empty();
        }
        long startedAt = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/systemone"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(buildRequest(message))))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = System.currentTimeMillis() - startedAt;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("jev intent classify responded {}; latencyMs={}", response.statusCode(), latencyMs);
                return Optional.empty();
            }
            Optional<IntentDecision> decision = Optional.of(parse(response.body(), latencyMs));
            decision.ifPresent(value -> log.info(
                    "jev intent classified: intent={}, confidence={}, complexity={}, latencyMs={}",
                    value.intent(), value.intentConfidence(), value.complexity(), value.latencyMs()));
            return decision;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception exception) {
            log.warn("jev intent classify failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    /** 请求体两问并行：意图 choice / 复杂度 score。 */
    Map<String, Object> buildRequest(String message) {
        return Map.of(
                "model", model,
                "state", message,
                "questions", Map.of(
                        INTENT_QUESTION, Map.of(
                                "type", "choice",
                                "instructions", "用户这条消息的主要意图是什么",
                                "criteria", Map.of(
                                        "工作任务", "读取或分析资料、查找公司制度流程或文档内容、操作项目、生成或处理文件、写代码、多步骤操作",
                                        "闲聊问答", "打招呼、常识问答、闲聊，无需执行任务或查阅资料；是否需要联网不由意图判断")),
                        COMPLEXITY_QUESTION, Map.of(
                                "type", "score",
                                "instructions", "处理这条消息需要多复杂",
                                "criteria", List.of("单轮可答", "需要多步骤", "需要研究分析"))));
    }

    /** 响应字段名以 docs.typesafe.ai/api/system-one.md 为准：answers.{name}.{choice/confidence/score}。 */
    IntentDecision parse(String responseBody, long latencyMs) throws Exception {
        JsonNode answers = objectMapper.readTree(responseBody).path("answers");
        JsonNode intent = answers.path(INTENT_QUESTION);
        return new IntentDecision(
                intent.path("choice").asText(""),
                intent.path("confidence").asDouble(0),
                answers.path(COMPLEXITY_QUESTION).path("score").asDouble(0),
                answers.path(COMPLEXITY_QUESTION).path("confidence").asDouble(0),
                latencyMs);
    }
}
