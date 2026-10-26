package com.zwx.zwxagent.platform.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Docling Serve 健康指示器：文档解析的可选降级依赖，不可用时 RAG 有本地回退，
 * 因此报 UNKNOWN（不拉低整体 UP），详情供运维面板与 Prometheus 抓取参考。
 */
@Component("docling")
public class DoclingHealthIndicator implements HealthIndicator {

    private final boolean enabled;
    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public DoclingHealthIndicator(@Value("${app.document-parser.docling.enabled:true}") boolean enabled,
                                  @Value("${app.document-parser.docling.base-url:http://127.0.0.1:5001}") String baseUrl) {
        this.enabled = enabled;
        this.baseUrl = baseUrl;
    }

    @Override
    public Health health() {
        if (!enabled) {
            return Health.unknown().withDetail("reason", "docling 解析已禁用，走本地回退").build();
        }
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(
                            URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Health.up().withDetail("baseUrl", baseUrl).build();
            }
            return Health.unknown().withDetail("baseUrl", baseUrl)
                    .withDetail("status", response.statusCode()).build();
        } catch (Exception exception) {
            return Health.unknown().withDetail("baseUrl", baseUrl)
                    .withDetail("error", String.valueOf(exception.getMessage())).build();
        }
    }
}
