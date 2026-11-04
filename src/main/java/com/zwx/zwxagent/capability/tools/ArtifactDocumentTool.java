package com.zwx.zwxagent.capability.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Word / PPT / PDF 交付物生成：排版交给 artifact 引擎，落盘与会话工作区由 backend 负责。
 * 引擎未配置时工具不会被注册（见 ToolFactory）。
 */
public class ArtifactDocumentTool {

    private static final Logger log = LoggerFactory.getLogger(ArtifactDocumentTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_MARKDOWN_CHARS = 200_000;
    private static final int MAX_SLIDES = 60;

    private final ToolSandbox sandbox;
    private final Path workDir;
    private final ArtifactClient client;

    public ArtifactDocumentTool(ToolSandbox sandbox, Path workDir, ArtifactClient client) {
        this.sandbox = sandbox;
        this.workDir = workDir;
        this.client = client;
    }

    @Tool(description = """
            Generate a Word .docx document from lightweight Markdown and save it in the task workspace.
            Supported syntax: '# / ## / ###' headings, '- ' or '* ' bullets, '1. ' numbered items,
            paragraphs, '**bold**' inline emphasis, and pipe tables ('| a | b |' with a '---' separator row).
            Use it for reports, specs, contracts and other deliverables that the user needs as a Word file.
            Returns JSON with the file path and byte size.""")
    public String createDocument(
            @ToolParam(description = "Destination file name relative to the task workspace, must end with .docx, e.g. report.docx") String fileName,
            @ToolParam(description = "Document title shown at the top, optional") String title,
            @ToolParam(description = "Markdown content (lightweight syntax as described)") String markdown) {
        if (markdown != null && markdown.length() > MAX_MARKDOWN_CHARS) {
            return "Error: markdown is too long (limit " + MAX_MARKDOWN_CHARS + " characters)";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title == null ? "" : title);
        body.put("markdown", markdown == null ? "" : markdown);
        return render("docx", fileName, ".docx", body);
    }

    @Tool(description = """
            Generate a PowerPoint .pptx deck and save it in the task workspace.
            slides must be a JSON array like [{"title":"背景","bullets":["市场增长","客户反馈"]}, ...];
            each slide gets a title plus bullet lines; a cover slide is created from 'title' when provided.
            Returns JSON with the file path and byte size.""")
    public String createPresentation(
            @ToolParam(description = "Destination file name relative to the task workspace, must end with .pptx, e.g. deck.pptx") String fileName,
            @ToolParam(description = "Deck title used for the cover slide, optional") String title,
            @ToolParam(description = "JSON array of slides: [{\"title\":\"...\",\"bullets\":[\"...\"]}]") String slides) {
        List<Map<String, Object>> parsed;
        try {
            parsed = parseSlides(slides);
        } catch (IllegalArgumentException exception) {
            return "Error: " + exception.getMessage();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title == null ? "" : title);
        body.put("slides", parsed);
        return render("pptx", fileName, ".pptx", body);
    }

    @Tool(description = """
            Generate a PDF from lightweight Markdown (headings, bullets, numbered lists, paragraphs)
            with Chinese support, and save it in the task workspace.
            Prefer this over generatePDF when the content is structured or long.
            Returns JSON with the file path and byte size.""")
    public String createPdfFromMarkdown(
            @ToolParam(description = "Destination file name relative to the task workspace, must end with .pdf, e.g. report.pdf") String fileName,
            @ToolParam(description = "Document title, optional") String title,
            @ToolParam(description = "Markdown content (lightweight syntax)") String markdown) {
        if (markdown != null && markdown.length() > MAX_MARKDOWN_CHARS) {
            return "Error: markdown is too long (limit " + MAX_MARKDOWN_CHARS + " characters)";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title == null ? "" : title);
        body.put("markdown", markdown == null ? "" : markdown);
        return render("pdf", fileName, ".pdf", body);
    }

    private String render(String kind, String fileName, String extension, Map<String, Object> body) {
        if (fileName == null || fileName.isBlank() || !fileName.trim().toLowerCase(Locale.ROOT).endsWith(extension)) {
            return "Error: fileName must end with " + extension;
        }
        if (!client.enabled()) {
            return "Error: artifact service is not configured (set APP_ARTIFACT_BASE_URL)";
        }
        Path target;
        try {
            target = sandbox.resolveWithin(workDir, fileName.trim());
            if (target.getParent() != null) Files.createDirectories(target.getParent());
        } catch (IOException | IllegalArgumentException exception) {
            return "Error: invalid file path: " + exception.getMessage();
        }
        try {
            byte[] bytes = client.post("/internal/render/" + kind, body);
            Files.write(target, bytes);
            log.info("Generated {} artifact {} ({} bytes)", kind, fileName, bytes.length);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("file", fileName.trim());
            result.put("bytes", bytes.length);
            return JSON.writeValueAsString(result);
        } catch (Exception exception) {
            log.warn("Artifact generation failed for {}: {}", fileName, exception.getMessage());
            return "Error: artifact generation failed: " + exception.getMessage();
        }
    }

    private List<Map<String, Object>> parseSlides(String slides) {
        if (slides == null || slides.isBlank()) throw new IllegalArgumentException("slides is required");
        JsonNode node;
        try {
            node = JSON.readTree(slides);
        } catch (IOException exception) {
            throw new IllegalArgumentException("slides is not valid JSON");
        }
        if (!node.isArray() || node.isEmpty()) throw new IllegalArgumentException("slides must be a non-empty JSON array");
        if (node.size() > MAX_SLIDES) throw new IllegalArgumentException("slides exceeds the limit of " + MAX_SLIDES);
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) throw new IllegalArgumentException("each slide must be a JSON object");
            Map<String, Object> slide = new LinkedHashMap<>();
            slide.put("title", item.path("title").asText(""));
            List<String> bullets = new ArrayList<>();
            JsonNode bulletNode = item.path("bullets");
            if (bulletNode.isArray()) {
                for (JsonNode bullet : bulletNode) {
                    bullets.add(bullet.asText(""));
                }
            }
            slide.put("bullets", bullets);
            result.add(slide);
        }
        return result;
    }
}
