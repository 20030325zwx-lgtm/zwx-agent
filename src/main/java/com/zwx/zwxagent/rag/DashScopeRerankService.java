package com.zwx.zwxagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DashScope 文本重排序（gte-rerank-v2）适配器。
 * 任何失败（未配置、超时、异常、响应不合法）都返回 empty，由调用方降级为向量检索原始顺序。
 */
@Slf4j
@Component
public class DashScopeRerankService {

    public record RerankHit(int index, double score) {
    }

    private static final String DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    private static final int MAX_DOCUMENTS = 20;
    private static final int MAX_DOCUMENT_CHARS = 1200;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient;
    private final String model;
    private final boolean enabled;

    public DashScopeRerankService(@Value("${spring.ai.dashscope.api-key:}") String apiKey,
                                  @Value("${app.rag.rerank.enabled:true}") boolean enabled,
                                  @Value("${app.rag.rerank.model:gte-rerank-v2}") String model,
                                  @Value("${app.rag.rerank.timeout-ms:1200}") long timeoutMs) {
        boolean keyPresent = apiKey != null && !apiKey.isBlank() && !"your-api-key".equals(apiKey.trim());
        this.enabled = enabled && keyPresent;
        this.model = model;
        if (this.enabled) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(500);
            factory.setReadTimeout((int) Math.max(200, timeoutMs));
            this.restClient = RestClient.builder().baseUrl(DEFAULT_ENDPOINT).requestFactory(factory).build();
            log.info("[rerank] 已启用 DashScope 重排序，model={}，timeout-ms={}", model, timeoutMs);
        } else {
            this.restClient = null;
            log.info("[rerank] 未启用（enabled={}，api-key 是否配置={}），检索结果保持向量原始顺序", enabled, keyPresent);
        }
    }

    /**
     * 对候选文档按与 query 的相关性重排。
     *
     * @return 按相关性降序的命中列表（index 对应入参 documents 下标）；不可用时返回 empty
     */
    public Optional<List<RerankHit>> rerank(String query, List<String> documents) {
        if (!enabled || restClient == null) return Optional.empty();
        if (query == null || query.isBlank() || documents == null || documents.size() <= 1) return Optional.empty();
        List<String> truncated = documents.stream().limit(MAX_DOCUMENTS).map(DashScopeRerankService::truncate).toList();
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("query", query);
            input.put("documents", truncated);
            Map<String, Object> body = Map.of("model", model, "input", input, "parameters", Map.of("return_documents", false));
            String json = restClient.post().uri("")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return Optional.ofNullable(parseResults(json, truncated.size()));
        } catch (Exception exception) {
            log.warn("[rerank] 重排序调用失败，降级为向量原始顺序：{}", exception.getMessage());
            return Optional.empty();
        }
    }

    /** 当前使用的重排模型名，用于检索 trace 展示。 */
    public String modelName() {
        return model;
    }

    /** 解析 DashScope rerank 响应；结构不合法视为失败返回 null。 */
    List<RerankHit> parseResults(String json, int documentCount) {
        try {
            JsonNode results = objectMapper.readTree(json).path("output").path("results");
            if (!results.isArray() || results.isEmpty()) return null;
            List<RerankHit> hits = new ArrayList<>();
            for (JsonNode item : results) {
                JsonNode index = item.path("index");
                JsonNode score = item.path("relevance_score");
                if (!index.canConvertToInt()) return null;
                int i = index.intValue();
                if (i < 0 || i >= documentCount) return null;
                hits.add(new RerankHit(i, score.isNumber() ? score.doubleValue() : 0.0));
            }
            return hits;
        } catch (Exception exception) {
            log.warn("[rerank] 响应解析失败，降级为向量原始顺序：{}", exception.getMessage());
            return null;
        }
    }

    private static String truncate(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_DOCUMENT_CHARS ? normalized : normalized.substring(0, MAX_DOCUMENT_CHARS);
    }
}
