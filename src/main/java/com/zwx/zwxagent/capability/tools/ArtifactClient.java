package com.zwx.zwxagent.capability.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * artifact 排版引擎的瘦客户端（services/artifact-mcp 暴露 /internal/render/*）。
 *
 * <p>为什么不是 MCP 工具直接生成：MCP 工具拿不到「租户/会话工作区」上下文，无法把二进制落到
 * 正确的工作区并复用既有签名下载。因此生成体量的重依赖放在独立服务，落盘与交付归 backend 工具。</p>
 */
public class ArtifactClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String token;
    private final int timeoutSeconds;
    private final int maxBytes;
    private final HttpClient httpClient;

    public ArtifactClient(String baseUrl, String token, int timeoutSeconds, int maxBytes) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.token = token == null ? "" : token.trim();
        this.timeoutSeconds = Math.max(10, timeoutSeconds);
        this.maxBytes = Math.max(1024 * 1024, maxBytes);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean enabled() {
        return !baseUrl.isBlank();
    }

    public byte[] post(String path, Map<String, Object> body) throws IOException, InterruptedException {
        if (!enabled()) throw new IllegalStateException("artifact service is not configured");
        String payload = JSON.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        if (!token.isBlank()) builder.header("X-Aster-Artifact-Token", token);
        HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            String message = new String(response.body(), StandardCharsets.UTF_8);
            if (message.length() > 300) message = message.substring(0, 300);
            throw new IOException("artifact service HTTP " + response.statusCode() + ": " + message);
        }
        byte[] bytes = response.body();
        if (bytes.length > maxBytes) {
            throw new IOException("generated artifact exceeds the limit of " + maxBytes + " bytes");
        }
        return bytes;
    }
}
