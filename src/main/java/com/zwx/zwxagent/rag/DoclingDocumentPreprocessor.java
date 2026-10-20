package com.zwx.zwxagent.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从 Docling converted document 中补充 chunk 接口默认丢弃的 page header/footer。
 * 这是文档预处理在 Java 侧的临时实现，后续应迁到独立预处理/切片服务。
 */
@Service
public class DoclingDocumentPreprocessor {

    private static final Set<String> FURNITURE_LABELS = Set.of("page_header", "page_footer");
    private static final Set<String> PAGE_NUMBER_PATTERNS = Set.of("第页", "page", "p.");
    private final int maxLines;
    private final int maxChars;

    public DoclingDocumentPreprocessor(@Value("${app.document-parser.docling.furniture-max-lines:10}") int maxLines,
                                       @Value("${app.document-parser.docling.furniture-max-chars:1000}") int maxChars) {
        this.maxLines = Math.max(1, maxLines);
        this.maxChars = Math.max(100, maxChars);
    }

    public PreprocessedDocument preprocess(DoclingChunkResponse response, String filename, List<DoclingChunk> bodyChunks) {
        String documentType = isResume(filename) ? PreprocessedDocument.RESUME : PreprocessedDocument.GENERAL;
        if (response == null || response.documents().isEmpty()) {
            return new PreprocessedDocument(documentType, List.of());
        }
        Map<String, Object> content = response.documents().get(0).content();
        if (content == null || !(content.get("json_content") instanceof Map<?, ?> document)) {
            return new PreprocessedDocument(documentType, List.of());
        }
        if (!(document.get("texts") instanceof List<?> texts)) {
            return new PreprocessedDocument(documentType, List.of());
        }
        String bodyText = bodyChunks == null ? "" : bodyChunks.stream()
                .map(chunk -> chunk.text() == null ? "" : chunk.text())
                .reduce("", (left, right) -> left + "\n" + right);
        Set<String> furniture = new LinkedHashSet<>();
        int chars = 0;
        for (Object value : texts) {
            if (!(value instanceof Map<?, ?> item)) continue;
            String label = stringValue(item.get("label")).toLowerCase(Locale.ROOT);
            String layer = stringValue(item.get("content_layer")).toLowerCase(Locale.ROOT);
            if (!"furniture".equals(layer) && !FURNITURE_LABELS.contains(label)) continue;
            String text = clean(stringValue(item.get("text")));
            if (text.isBlank() || isPageNumber(text) || bodyText.contains(text)) continue;
            if (furniture.size() >= maxLines || chars + text.length() > maxChars) break;
            furniture.add(text);
            chars += text.length();
        }
        return new PreprocessedDocument(documentType, new ArrayList<>(furniture));
    }

    public static boolean isResume(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return lower.contains("简历") || lower.contains("resume") || lower.contains("curriculum") || lower.contains("cv");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private boolean isPageNumber(String text) {
        if (text.matches("\\d{1,3}")) return true;
        String normalized = text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (normalized.matches("第\\d{1,3}页")) return true;
        if (normalized.matches("page\\d{1,3}")) return true;
        return PAGE_NUMBER_PATTERNS.stream().anyMatch(pattern -> normalized.equals(pattern + "1"));
    }
}
