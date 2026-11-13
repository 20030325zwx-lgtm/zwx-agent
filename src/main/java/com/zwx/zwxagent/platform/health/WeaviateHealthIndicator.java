package com.zwx.zwxagent.platform.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Weaviate 健康指示器（仅 provider=weaviate 时注册）：就绪端点探测。
 * 向量库不可用不阻断应用启动（检索降级 FAILED），因此报 UNKNOWN（不拉低整体 UP）。
 */
@Component("weaviate")
@ConditionalOnProperty(name = "app.vector-store.provider", havingValue = "weaviate")
public class WeaviateHealthIndicator implements HealthIndicator {

    private final String readyUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public WeaviateHealthIndicator(
            @Value("${app.weaviate.scheme:http}") String scheme,
            @Value("${app.weaviate.host:127.0.0.1}") String host,
            @Value("${app.weaviate.http-port:8080}") int httpPort) {
        this.readyUrl = scheme + "://" + host + ":" + httpPort + "/v1/.well-known/ready";
    }

    @Override
    public Health health() {
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(readyUrl))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Health.up().withDetail("readyUrl", readyUrl).build();
            }
            return Health.unknown().withDetail("readyUrl", readyUrl)
                    .withDetail("status", response.statusCode()).build();
        } catch (Exception exception) {
            return Health.unknown().withDetail("readyUrl", readyUrl)
                    .withDetail("error", String.valueOf(exception.getMessage())).build();
        }
    }
}
