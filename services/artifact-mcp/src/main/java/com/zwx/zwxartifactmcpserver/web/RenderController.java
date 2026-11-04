package com.zwx.zwxartifactmcpserver.web;

import com.zwx.zwxartifactmcpserver.render.ConversionService;
import com.zwx.zwxartifactmcpserver.render.DocumentRenderer;
import com.zwx.zwxartifactmcpserver.render.PdfRenderer;
import com.zwx.zwxartifactmcpserver.render.PresentationRenderer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 无状态排版引擎：backend 工具负责把产物落到会话工作区，这里只负责把结构化输入渲染成二进制。
 * 设计原因见 services/artifact-mcp/README.md（MCP 工具拿不到租户/工作区上下文）。
 */
@RestController
@RequestMapping("/internal/render")
public class RenderController {

    private static final Logger log = LoggerFactory.getLogger(RenderController.class);
    private static final int MAX_MARKDOWN_CHARS = 2_000_000;
    private static final int MAX_SOURCE_BYTES = 20 * 1024 * 1024;

    private final DocumentRenderer documentRenderer;
    private final PresentationRenderer presentationRenderer;
    private final PdfRenderer pdfRenderer;
    private final ConversionService conversionService;
    private final String internalToken;

    public RenderController(DocumentRenderer documentRenderer,
                            PresentationRenderer presentationRenderer,
                            PdfRenderer pdfRenderer,
                            ConversionService conversionService,
                            @Value("${artifact.internal-token:}") String internalToken) {
        this.documentRenderer = documentRenderer;
        this.presentationRenderer = presentationRenderer;
        this.pdfRenderer = pdfRenderer;
        this.conversionService = conversionService;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    public record DocxRequest(String title, String markdown, String templateBase64, Map<String, String> values) {
    }

    public record PptxRequest(String title, List<PresentationRenderer.Slide> slides) {
    }

    public record PdfRequest(String title, String markdown) {
    }

    public record ConvertRequest(String fileName, String contentBase64, String target) {
    }

    @PostMapping("/docx")
    public ResponseEntity<byte[]> docx(@RequestBody DocxRequest request, HttpServletRequest servlet) {
        if (!authorized(servlet)) return unauthorized();
        try {
            byte[] bytes;
            if (request.templateBase64() != null && !request.templateBase64().isBlank()) {
                byte[] template = Base64.getDecoder().decode(request.templateBase64());
                if (template.length > MAX_SOURCE_BYTES) return tooLarge();
                bytes = documentRenderer.fillTemplate(template,
                        request.values() == null ? Map.of() : request.values());
            } else {
                if (request.markdown() != null && request.markdown().length() > MAX_MARKDOWN_CHARS) return tooLarge();
                bytes = documentRenderer.render(request.title(), request.markdown());
            }
            return binary(bytes, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
        } catch (Exception exception) {
            return failure("docx", exception);
        }
    }

    @PostMapping("/pptx")
    public ResponseEntity<byte[]> pptx(@RequestBody PptxRequest request, HttpServletRequest servlet) {
        if (!authorized(servlet)) return unauthorized();
        try {
            byte[] bytes = presentationRenderer.render(request.title(), request.slides());
            return binary(bytes, "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
        } catch (Exception exception) {
            return failure("pptx", exception);
        }
    }

    @PostMapping("/pdf")
    public ResponseEntity<byte[]> pdf(@RequestBody PdfRequest request, HttpServletRequest servlet) {
        if (!authorized(servlet)) return unauthorized();
        try {
            if (request.markdown() != null && request.markdown().length() > MAX_MARKDOWN_CHARS) return tooLarge();
            byte[] bytes = pdfRenderer.render(request.title(), request.markdown());
            return binary(bytes, MediaType.APPLICATION_PDF_VALUE, "pdf");
        } catch (Exception exception) {
            return failure("pdf", exception);
        }
    }

    @PostMapping("/convert")
    public ResponseEntity<byte[]> convert(@RequestBody ConvertRequest request, HttpServletRequest servlet) {
        if (!authorized(servlet)) return unauthorized();
        try {
            if (request.contentBase64() == null || request.contentBase64().isBlank()) {
                return ResponseEntity.badRequest().body("contentBase64 is required".getBytes());
            }
            byte[] source = Base64.getDecoder().decode(request.contentBase64());
            if (source.length > MAX_SOURCE_BYTES) return tooLarge();
            byte[] bytes = conversionService.toPdf(request.fileName(), source);
            return binary(bytes, MediaType.APPLICATION_PDF_VALUE, "pdf");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure("convert", interrupted);
        } catch (Exception exception) {
            return failure("convert", exception);
        }
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of(
                "service", "aster-artifact-mcp",
                "renders", List.of("docx", "pptx", "pdf"),
                "converts", List.of("docx->pdf", "pptx->pdf", "xlsx->pdf"),
                "sofficeAvailable", conversionService.resolveSoffice() != null,
                "pdfFontAvailable", pdfRenderer.resolveFontPath() != null);
    }

    private boolean authorized(HttpServletRequest servlet) {
        if (internalToken.isEmpty()) return true;
        String provided = servlet.getHeader("X-Aster-Artifact-Token");
        return internalToken.equals(provided);
    }

    private ResponseEntity<byte[]> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid artifact token".getBytes());
    }

    private ResponseEntity<byte[]> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body("payload too large".getBytes());
    }

    private ResponseEntity<byte[]> binary(byte[] bytes, String contentType, String kind) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header("X-Artifact-Kind", kind)
                .header("X-Artifact-Bytes", String.valueOf(bytes.length))
                .body(bytes);
    }

    private ResponseEntity<byte[]> failure(String kind, Exception exception) {
        log.warn("Artifact render {} failed: {}", kind, exception.getMessage());
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(message.getBytes());
    }
}
