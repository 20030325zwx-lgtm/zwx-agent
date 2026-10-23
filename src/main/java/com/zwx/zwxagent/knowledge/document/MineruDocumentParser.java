package com.zwx.zwxagent.knowledge.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** MinerU 精准解析 API 客户端：申请上传地址 -> 上传 -> 轮询 -> 下载并采集完整结果包。 */
@Component
public class MineruDocumentParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String token;
    private final String baseUrl;
    private final String modelVersion;
    private final String language;
    private final long timeoutSeconds;
    private final long pollIntervalMillis;
    private final int maxZipBytes;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public MineruDocumentParser(@Value("${app.document-parser.mineru.token:}") String token,
                        @Value("${app.document-parser.mineru.base-url:https://mineru.net}") String baseUrl,
                        @Value("${app.document-parser.mineru.model-version:vlm}") String modelVersion,
                        @Value("${app.document-parser.mineru.language:ch}") String language,
                        @Value("${app.document-parser.mineru.timeout-seconds:600}") long timeoutSeconds,
                        @Value("${app.document-parser.mineru.poll-interval-ms:3000}") long pollIntervalMillis,
                        @Value("${app.document-parser.mineru.max-zip-bytes:104857600}") int maxZipBytes) {
        this.token = token == null ? "" : token.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank() ? "https://mineru.net" : baseUrl).replaceAll("/+$", "");
        this.modelVersion = modelVersion == null || modelVersion.isBlank() ? "vlm" : modelVersion;
        this.language = language == null || language.isBlank() ? "ch" : language;
        this.timeoutSeconds = Math.max(30, timeoutSeconds);
        this.pollIntervalMillis = Math.max(500, pollIntervalMillis);
        this.maxZipBytes = Math.max(1024 * 1024, maxZipBytes);
    }

    public boolean isConfigured() {
        return !token.isBlank();
    }

    public boolean supports(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".ppt") || lower.endsWith(".pptx")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx")
                || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    /**
     * 使用 MinerU 文件上传模式解析并保留完整结果包。
     * 使用文件上传而非 URL，避免签名 URL 过期和 MinerU 访问内网/境外 URL 失败。
     */
    public MineruParseResult parse(byte[] bytes, String filename) {
        if (!isConfigured()) throw new MineruException("MINERU_TOKEN is not configured");
        try {
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("name", filename);
            file.put("data_id", UUID.randomUUID().toString().replace("-", ""));
            file.put("is_ocr", true);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("files", java.util.List.of(file));
            body.put("model_version", modelVersion);
            body.put("language", language);
            body.put("enable_table", true);
            body.put("enable_formula", true);
            body.put("extra_formats", java.util.List.of("docx", "html", "latex"));

            JsonNode submitted = postJson("/api/v4/file-urls/batch", body);
            if (submitted.path("code").asInt(-1) != 0) {
                throw new MineruException("MinerU submit failed: " + submitted.path("msg").asText("unknown error"));
            }
            String batchId = submitted.path("data").path("batch_id").asText("");
            String uploadUrl = submitted.path("data").path("file_urls").path(0).asText("");
            if (batchId.isBlank() || uploadUrl.isBlank()) {
                throw new MineruException("MinerU did not return an upload URL");
            }
            upload(uploadUrl, bytes);
            JsonNode result = poll(batchId, filename);
            String zipUrl = result.path("full_zip_url").asText("");
            if (zipUrl.isBlank()) throw new MineruException("MinerU returned no result zip");
            return extractResult(downloadResultZip(zipUrl));
        } catch (MineruException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MineruException("MinerU request failed: " + safeMessage(exception), exception);
        }
    }

    private JsonNode postJson(String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new MineruException("MinerU HTTP " + response.statusCode());
        }
        return JSON.readTree(response.body());
    }

    private void upload(String uploadUrl, byte[] bytes) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uploadUrl))
                .timeout(Duration.ofSeconds(120))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new MineruException("MinerU file upload failed: HTTP " + response.statusCode());
        }
    }

    private JsonNode poll(String batchId, String filename) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        String lastState = "unknown";
        while (System.currentTimeMillis() < deadline) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v4/extract-results/batch/" + batchId))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MineruException("MinerU poll failed: HTTP " + response.statusCode());
            }
            JsonNode root = JSON.readTree(response.body());
            if (root.path("code").asInt(-1) != 0) {
                throw new MineruException("MinerU poll failed: " + root.path("msg").asText("unknown error"));
            }
            JsonNode results = root.path("data").path("extract_result");
            JsonNode match = null;
            if (results.isArray()) {
                for (JsonNode item : results) {
                    if (filename.equals(item.path("file_name").asText())) {
                        match = item;
                        break;
                    }
                    if (match == null) match = item;
                }
            }
            if (match == null) {
                lastState = "missing";
            } else {
                lastState = match.path("state").asText("unknown");
                if ("done".equals(lastState)) return match;
                if ("failed".equals(lastState)) {
                    throw new MineruException("MinerU parse failed: " + match.path("err_msg").asText("unknown error"));
                }
            }
            Thread.sleep(pollIntervalMillis);
        }
        throw new MineruException("MinerU parse timed out; last state=" + lastState);
    }

    private byte[] downloadResultZip(String zipUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(zipUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new MineruException("MinerU result download failed: HTTP " + response.statusCode());
        }
        byte[] zip = response.body();
        if (zip.length > maxZipBytes) throw new MineruException("MinerU result zip is too large");
        return zip;
    }

    MineruParseResult extractResult(byte[] zip) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String fallbackMarkdown = null;
        long expandedBytes = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    expandedBytes += read;
                    if (expandedBytes > maxZipBytes) throw new MineruException("MinerU expanded result is too large");
                }
                byte[] contents = output.toByteArray();
                files.put(name, contents);
                if (name.endsWith(".md")) {
                    String markdown = new String(contents, StandardCharsets.UTF_8);
                    if (name.equals("full.md") || name.endsWith("/full.md")) fallbackMarkdown = markdown;
                    else if (fallbackMarkdown == null) fallbackMarkdown = markdown;
                }
            }
        }
        if (files.isEmpty()) throw new MineruException("MinerU result zip is empty");
        if (fallbackMarkdown == null) throw new MineruException("MinerU result zip contains no Markdown");
        return new MineruParseResult(fallbackMarkdown, files);
    }

    private String safeMessage(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value.replaceAll("[\\r\\n]", " ");
    }

    public static class MineruException extends RuntimeException {
        public MineruException(String message) {
            super(message);
        }

        public MineruException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
