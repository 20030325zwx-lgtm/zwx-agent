package com.zwx.zwxagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class DoclingServeClient {

    private final boolean enabled;
    private final String baseUrl;
    private final Set<String> formats;
    private final boolean doOcr;
    private final boolean doTableStructure;
    private final String tableMode;
    private final RestClient restClient;

    public DoclingServeClient(@Value("${app.document-parser.docling.enabled:true}") boolean enabled,
                              @Value("${app.document-parser.docling.base-url:http://127.0.0.1:5001}") String baseUrl,
                              @Value("${app.document-parser.docling.timeout-seconds:180}") long timeoutSeconds,
                              @Value("${app.document-parser.docling.formats:pdf,doc,docx,xls,xlsx,ppt,pptx}") String formats,
                              @Value("${app.document-parser.docling.do-ocr:true}") boolean doOcr,
                              @Value("${app.document-parser.docling.do-table-structure:true}") boolean doTableStructure,
                              @Value("${app.document-parser.docling.table-mode:accurate}") String tableMode) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.formats = Stream.of((formats == null ? "" : formats).split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        this.doOcr = doOcr;
        this.doTableStructure = doTableStructure;
        this.tableMode = tableMode == null || tableMode.isBlank() ? "accurate" : tableMode;
        if (enabled && !this.baseUrl.isBlank()) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
            factory.setReadTimeout((int) Duration.ofSeconds(Math.max(10, timeoutSeconds)).toMillis());
            this.restClient = RestClient.builder().baseUrl(this.baseUrl).requestFactory(factory).build();
        } else {
            this.restClient = null;
        }
    }

    public boolean enabled() {
        return enabled && restClient != null;
    }

    public boolean supports(String filename) {
        if (!enabled()) return false;
        return formats.contains(extension(filename));
    }

    public DoclingChunkResponse chunk(byte[] bytes, String filename) {
        if (!enabled()) throw new DoclingServeException("Docling Serve is not enabled");
        try {
            MultipartBodyBuilder body = new MultipartBodyBuilder();
            body.part("files", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            }).contentType(MediaType.APPLICATION_OCTET_STREAM);
            body.part("convert_from_formats", extension(filename));
            body.part("convert_do_ocr", String.valueOf(doOcr));
            body.part("convert_do_table_structure", String.valueOf(doTableStructure));
            body.part("convert_table_mode", tableMode);
            DoclingChunkResponse response = restClient.post()
                    .uri("/v1/chunk/hybrid/file")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body.build())
                    .retrieve()
                    .body(DoclingChunkResponse.class);
            if (response == null) throw new DoclingServeException("Docling Serve returned an empty response");
            return response;
        } catch (DoclingServeException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Docling Serve request failed; baseUrl={}, filename={}, error={}", baseUrl, filename, exception.getMessage());
            throw new DoclingServeException("Docling Serve request failed: " + exception.getMessage(), exception);
        }
    }

    private String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
