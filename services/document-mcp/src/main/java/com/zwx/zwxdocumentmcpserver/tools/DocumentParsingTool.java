package com.zwx.zwxdocumentmcpserver.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DocumentParsingTool {

    private static final Logger log = LoggerFactory.getLogger(DocumentParsingTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_MAX_CHARS = 20000;
    private static final int HARD_MAX_CHARS = 50000;

    private final MineruClient mineru;
    private final String provider;
    private final boolean fallbackToDocling;
    private final boolean doclingEnabled;
    private final RestClient docling;
    private final RestClient tika;
    private final List<String> allowedHosts;
    private final Set<String> doclingFormats;
    private final int maxBytes;

    public DocumentParsingTool(MineruClient mineru,
                               @Value("${document.provider:mineru}") String provider,
                               @Value("${document.fallback-to-docling:true}") boolean fallbackToDocling,
                               @Value("${document.docling.enabled:false}") boolean doclingEnabled,
                               @Value("${document.docling.base-url:http://127.0.0.1:5001}") String doclingBaseUrl,
                               @Value("${document.docling.timeout-seconds:660}") long doclingTimeoutSeconds,
                               @Value("${document.tika.base-url:}") String tikaBaseUrl,
                               @Value("${document.allowed-hosts:*.aliyuncs.com,localhost,127.0.0.1}") String allowedHosts,
                               @Value("${document.docling.formats:pdf,doc,docx,xls,xlsx,ppt,pptx}") String doclingFormats,
                               @Value("${document.max-bytes:20971520}") int maxBytes) {
        this.mineru = mineru;
        this.provider = provider == null || provider.isBlank() ? "mineru" : provider.trim().toLowerCase(Locale.ROOT);
        this.fallbackToDocling = fallbackToDocling;
        this.doclingEnabled = doclingEnabled && doclingBaseUrl != null && !doclingBaseUrl.isBlank();
        this.docling = this.doclingEnabled ? RestClient.builder()
                .baseUrl(doclingBaseUrl.trim())
                .requestFactory(requestFactory(doclingTimeoutSeconds))
                .build() : null;
        this.tika = tikaBaseUrl == null || tikaBaseUrl.isBlank() ? null : RestClient.builder()
                .baseUrl(tikaBaseUrl.trim())
                .requestFactory(requestFactory(60))
                .build();
        this.allowedHosts = Stream.of(allowedHosts.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList();
        this.doclingFormats = Stream.of(doclingFormats.split(",")).map(String::trim).map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
        this.maxBytes = Math.max(1024, maxBytes);
    }

    @Tool(name = "parse_document",
          description = "Download and parse a PDF/Office/Markdown/TXT document. Returns structured Markdown or plain text.")
    public String parseDocument(@ToolParam(description = "Short-lived public document URL") String document_url,
                                @ToolParam(required = false, description = "Optional filename used to detect the format") String filename,
                                @ToolParam(required = false, description = "Output format: markdown or text; default markdown") String format,
                                @ToolParam(required = false, description = "Maximum returned characters, default 20000, max 50000") Integer max_chars) {
        try {
            if (document_url == null || document_url.isBlank()) return error("document_url is required");
            URI uri = validateUrl(document_url);
            String outputFormat = format == null || format.isBlank() ? "markdown" : format.trim().toLowerCase(Locale.ROOT);
            if (!outputFormat.equals("markdown") && !outputFormat.equals("text")) {
                return error("format must be markdown or text");
            }
            String resolvedFilename = resolveFilename(filename, uri);
            String extension = extension(resolvedFilename);
            byte[] bytes = download(uri);
            Parsed parsed = null;
            if ("mineru".equals(provider) && mineru.isConfigured()) {
                try {
                    parsed = new Parsed(mineru.parse(bytes, resolvedFilename), "mineru");
                } catch (Exception exception) {
                    log.warn("MinerU parse failed; filename={}, reason={}", resolvedFilename, safeMessage(exception));
                }
            }
            if (parsed == null && doclingEnabled && doclingFormats.contains(extension)
                    && ("docling".equals(provider) || fallbackToDocling)) {
                parsed = tryDocling(bytes, resolvedFilename, outputFormat);
            }
            if (parsed == null && tika != null) {
                parsed = tryTika(bytes, resolvedFilename, extension, outputFormat);
            }
            if (parsed == null && (extension.equals("md") || extension.equals("markdown") || extension.equals("txt") || extension.isBlank())) {
                String text = decodeText(bytes, extension);
                parsed = new Parsed(text, "text");
            }
            if (parsed == null) {
                return error("Unsupported document format or no available parser: " + extension);
            }
            String content = parsed.content();
            if (content == null || content.isBlank()) return error("Parser returned no content");
            int limit = max_chars == null ? DEFAULT_MAX_CHARS : Math.max(100, Math.min(HARD_MAX_CHARS, max_chars));
            boolean truncated = content.length() > limit;
            if (truncated) content = content.substring(0, limit);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("filename", resolvedFilename);
            result.put("source", parsed.source());
            result.put("format", outputFormat);
            result.put("chars", content.length());
            result.put("truncated", truncated);
            result.put("content", content);
            return JSON.writeValueAsString(result);
        } catch (Exception exception) {
            return error(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    private Parsed tryDocling(byte[] bytes, String filename, String outputFormat) {
        try {
            MultipartBodyBuilder body = new MultipartBodyBuilder();
            body.part("files", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            }).contentType(MediaType.APPLICATION_OCTET_STREAM);
            body.part("convert_from_formats", extension(filename));
            body.part("convert_do_ocr", "true");
            body.part("convert_do_table_structure", "true");
            body.part("convert_table_mode", "accurate");
            body.part("include_converted_doc", "true");
            DoclingChunkResponse response = docling.post()
                    .uri("/v1/chunk/hybrid/file")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body.build())
                    .retrieve()
                    .body(DoclingChunkResponse.class);
            if (response == null || response.chunks() == null || response.chunks().isEmpty()) return null;
            StringBuilder content = new StringBuilder();
            String previousHeadings = null;
            for (DoclingChunk chunk : response.chunks()) {
                if (chunk.text() == null || chunk.text().isBlank()) continue;
                String text = chunk.text().trim();
                String headings = chunk.headings() == null || chunk.headings().isEmpty() ? null : String.join(" > ", chunk.headings());
                if (!content.isEmpty()) content.append("\n\n");
                if (outputFormat.equals("markdown") && headings != null && !headings.equals(previousHeadings)
                        && !text.startsWith("#")) {
                    content.append("## ").append(headings).append("\n\n");
                }
                content.append(text);
                previousHeadings = headings;
            }
            return content.isEmpty() ? null : new Parsed(content.toString().trim(), "docling");
        } catch (Exception ignored) {
            return null;
        }
    }

    private Parsed tryTika(byte[] bytes, String filename, String extension, String outputFormat) {
        try {
            String html = tika.post().uri("/tika")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .accept(MediaType.TEXT_HTML)
                    .body(bytes)
                    .retrieve().body(String.class);
            if (html == null || html.isBlank()) return null;
            org.jsoup.nodes.Document document = Jsoup.parse(html);
            if (document.body() == null) return null;
            String text = document.body().text().trim();
            return text.isBlank() ? null : new Parsed(text, "tika");
        } catch (Exception ignored) {
            return null;
        }
    }

    private byte[] download(URI uri) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "zwx-document-mcp/0.1");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("Document download failed: HTTP " + status);
        int declared = connection.getContentLength();
        if (declared > maxBytes) throw new IllegalStateException("Document is larger than " + maxBytes + " bytes");
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                if (output.size() > maxBytes) throw new IllegalStateException("Document is larger than " + maxBytes + " bytes");
            }
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private URI validateUrl(String rawUrl) {
        URI uri;
        try { uri = URI.create(rawUrl.trim()); }
        catch (Exception exception) { throw new IllegalArgumentException("Invalid document_url"); }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("document_url must be a credential-free HTTP(S) URL");
        }
        boolean allowed = allowedHosts.stream().anyMatch(pattern ->
                pattern.startsWith("*.") ? uri.getHost().toLowerCase(Locale.ROOT).endsWith(pattern.substring(1)) : uri.getHost().equalsIgnoreCase(pattern));
        if (!allowed) throw new IllegalArgumentException("document host is not allowed");
        return uri;
    }

    private String resolveFilename(String filename, URI uri) {
        String value = filename == null ? "" : filename.trim();
        if (value.isBlank()) {
            String path = uri.getPath() == null ? "" : uri.getPath();
            int slash = path.lastIndexOf('/');
            value = slash >= 0 ? path.substring(slash + 1) : path;
            try { value = URLDecoder.decode(value, StandardCharsets.UTF_8); } catch (Exception ignored) { }
        }
        if (value.isBlank()) value = "document";
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }

    private String extension(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String decodeText(byte[] bytes, String extension) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private String safeMessage(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value.replaceAll("[\\r\\n]", " ");
    }

    private String error(String message) {
        try { return JSON.writeValueAsString(Map.of("error", message == null ? "unknown error" : message)); }
        catch (Exception exception) { return "{\"error\":\"unknown error\"}"; }
    }

    private static SimpleClientHttpRequestFactory requestFactory(long timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(Math.max(10, timeoutSeconds)).toMillis());
        return factory;
    }

    private record Parsed(String content, String source) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DoclingChunkResponse(List<DoclingChunk> chunks,
                                        @JsonProperty("processing_time") Double processingTime) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DoclingChunk(@JsonProperty("chunk_index") Integer chunkIndex, String text,
                                List<String> headings, @JsonProperty("page_numbers") List<Integer> pageNumbers) { }
}
