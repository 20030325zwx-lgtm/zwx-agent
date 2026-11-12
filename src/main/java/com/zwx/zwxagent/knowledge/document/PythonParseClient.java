package com.zwx.zwxagent.knowledge.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Locale;
import java.util.Set;

/**
 * 统一解析服务（Python sidecar）客户端。
 *
 * 契约：POST {base-url}/parse（multipart: file/engine）→ zip（full.md + content_list.json + images）。
 * 返回包直接交给 {@link MineruDocumentParser#extractResult(byte[], long)} 解包，
 * 从而完整复用 MinerU 的块解析、落库、切片与图纸增强管线。
 */
@Component
public class PythonParseClient {

    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx");

    private final boolean enabled;
    private final String baseUrl;
    private final long maxZipBytes;
    private final RestClient restClient;

    public PythonParseClient(
            @Value("${app.document-parser.python.enabled:false}") boolean enabled,
            @Value("${app.document-parser.python.base-url:http://127.0.0.1:8131}") String baseUrl,
            @Value("${app.document-parser.python.timeout-seconds:300}") long timeoutSeconds,
            @Value("${app.document-parser.python.max-zip-bytes:104857600}") long maxZipBytes) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.maxZipBytes = maxZipBytes;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout((int) Math.max(5, timeoutSeconds) * 1000);
        this.restClient = RestClient.builder().baseUrl(this.baseUrl).requestFactory(factory).build();
    }

    public boolean isConfigured() {
        return enabled && !baseUrl.isBlank();
    }

    public boolean supports(String filename) {
        if (!isConfigured() || filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return false;
        return SUPPORTED_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** 解析文件字节，返回 MinerU 等价结果包；失败抛 {@link PythonParseException}。 */
    public MineruParseResult parse(byte[] bytes, String filename) {
        if (!isConfigured()) throw new PythonParseException("Parse service is not configured");
        if (bytes == null || bytes.length == 0) throw new PythonParseException("Empty document");
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename == null ? "input" : filename;
            }
        });
        body.add("engine", "docling");
        byte[] zip;
        try {
            zip = restClient.post()
                    .uri("/parse")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientException exception) {
            throw new PythonParseException("Parse service request failed: " + exception.getMessage(), exception);
        }
        if (zip == null || zip.length == 0) {
            throw new PythonParseException("Parse service returned an empty result");
        }
        try {
            return MineruDocumentParser.extractResult(zip, maxZipBytes);
        } catch (Exception exception) {
            throw new PythonParseException("Parse service result unreadable: " + exception.getMessage(), exception);
        }
    }
}
