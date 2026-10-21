package com.zwx.zwxagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class DoclingServeChunkingService {

    private static final java.util.regex.Pattern DOT_LEADER = java.util.regex.Pattern.compile("[.．·]{4,}");
    private static final java.util.regex.Pattern TOC_PAGE_REF = java.util.regex.Pattern.compile(
            "(?i).*[.．·]{4,}\\s*(?:[0-9]+|[ivxlcdm]+)\\s*$");
    private static final java.util.regex.Pattern CHAPTER_MARKER = java.util.regex.Pattern.compile(
            "第\\s*[一二三四五六七八九十百千零〇]+\\s*[章节条]");

    private final DoclingServeClient client;
    private final DoclingDocumentPreprocessor preprocessor;
    private final boolean fallbackToLocal;
    private final boolean furnitureChunkEnabled;
    private final TocHandling tocHandling;

    public DoclingServeChunkingService(DoclingServeClient client,
                                       DoclingDocumentPreprocessor preprocessor,
                                       @Value("${app.document-parser.docling.fallback-to-local:true}") boolean fallbackToLocal,
                                       @Value("${app.document-parser.docling.furniture-chunk-enabled:true}") boolean furnitureChunkEnabled,
                                       @Value("${app.document-parser.docling.toc-handling:merge}") String tocHandling) {
        this.client = client;
        this.preprocessor = preprocessor;
        this.fallbackToLocal = fallbackToLocal;
        this.furnitureChunkEnabled = furnitureChunkEnabled;
        this.tocHandling = TocHandling.from(tocHandling);
    }

    public boolean shouldUse(String filename) {
        return client.supports(filename);
    }

    public boolean fallbackToLocal() {
        return fallbackToLocal;
    }

    public List<Document> chunk(byte[] bytes, String filename, Map<String, Object> baseMetadata) {
        DoclingChunkResponse response = client.chunk(bytes, filename);
        List<DoclingChunk> sourceChunks = response.chunks();
        if (sourceChunks == null || sourceChunks.isEmpty()) {
            throw new DoclingServeException("Docling Serve returned no chunks");
        }
        PreprocessedDocument preprocessed = preprocessor.preprocess(response, filename, sourceChunks);
        boolean resume = preprocessed.resume();
        List<Document> chunks = new ArrayList<>();
        for (DoclingChunk source : sourceChunks) {
            if (source.text() == null || source.text().isBlank()) continue;
            Map<String, Object> metadata = new HashMap<>(baseMetadata);
            metadata.put("parser", "docling-serve");
            metadata.put("chunking", "docling-hybrid");
            metadata.put("documentType", preprocessed.documentType());
            if (source.chunkIndex() != null) metadata.put("doclingChunkIndex", source.chunkIndex());
            if (source.numTokens() != null) metadata.put("tokenCount", source.numTokens());
            if (source.headings() != null && !source.headings().isEmpty()) {
                metadata.put("sectionPath", String.join(" > ", source.headings()));
                metadata.put("headings", source.headings());
            }
            if (source.pageNumbers() != null && !source.pageNumbers().isEmpty()) {
                metadata.put("pageNumber", source.pageNumbers().get(0));
                metadata.put("pageNumbers", new LinkedHashSet<>(source.pageNumbers()));
            }
            if (source.captions() != null && !source.captions().isEmpty()) metadata.put("captions", source.captions());
            if (source.docItems() != null && !source.docItems().isEmpty()) metadata.put("docItems", source.docItems());
            if (source.metadata() != null) metadata.put("doclingMetadata", source.metadata());
            if (resume) metadata.put("resumeSection", resumeSection(metadata, source.text()));
            chunks.add(Document.builder().text(source.text()).metadata(metadata).build());
        }
        if (chunks.isEmpty()) throw new DoclingServeException("Docling Serve returned only empty chunks");
        chunks = settleTocChunks(chunks, response);
        if (chunks.isEmpty()) throw new DoclingServeException("Docling Serve returned only empty chunks");
        if (furnitureChunkEnabled && !preprocessed.furnitureText().isEmpty()) {
            String section = resume ? "基本信息" : "文档头部信息";
            String text = "[" + section + "]\n" + String.join("\n", preprocessed.furnitureText());
            Map<String, Object> metadata = new HashMap<>(baseMetadata);
            metadata.put("parser", "docling-serve");
            metadata.put("chunking", "docling-furniture");
            metadata.put("documentType", preprocessed.documentType());
            metadata.put("sectionPath", section);
            metadata.put("contentLayer", "furniture");
            if (resume) metadata.put("resumeSection", "基本信息");
            chunks.add(0, Document.builder().text(text).metadata(metadata).build());
        }
        return chunks;
    }

    private List<Document> settleTocChunks(List<Document> chunks, DoclingChunkResponse response) {
        if (tocHandling == TocHandling.KEEP) return chunks;
        List<Document> result = new ArrayList<>();
        List<Document> buffer = new ArrayList<>();
        for (Document chunk : chunks) {
            if (isTocChunk(chunk)) {
                buffer.add(chunk);
                continue;
            }
            flushToc(result, buffer, response);
            result.add(chunk);
        }
        flushToc(result, buffer, response);
        return result;
    }

    private void flushToc(List<Document> result, List<Document> buffer, DoclingChunkResponse response) {
        if (buffer.isEmpty()) return;
        if (tocHandling == TocHandling.MERGE) {
            Document merged = mergeToc(buffer, response);
            if (merged != null) result.add(merged);
        }
        buffer.clear();
    }

    /** 目录省略号会被 tokenizer 放大，合并后去掉点线并只保留一个目录标题。 */
    private Document mergeToc(List<Document> buffer, DoclingChunkResponse response) {
        Map<String, Object> metadata = new HashMap<>(buffer.get(0).getMetadata());
        LinkedHashSet<Integer> pages = new LinkedHashSet<>();
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        int sourceTokens = 0;
        for (Document chunk : buffer) {
            Object pageValue = chunk.getMetadata().get("pageNumbers");
            if (pageValue instanceof java.util.Collection<?> collection) {
                for (Object page : collection) {
                    if (page instanceof Number number) pages.add(number.intValue());
                }
            }
            Object tokenValue = chunk.getMetadata().get("tokenCount");
            if (tokenValue instanceof Number number) sourceTokens += number.intValue();
            for (String line : chunk.getText().split("\n")) {
                String cleaned = DOT_LEADER.matcher(line).replaceAll(" ")
                        .replaceAll("[\\t ]{2,}", " ").trim();
                if (cleaned.isEmpty() || isTocHeading(cleaned)) continue;
                lines.add(cleaned);
            }
        }
        List<String> rebuilt = reconstructTocEntries(response, buffer);
        String tocText;
        if (rebuilt.size() >= 2) {
            tocText = "[目录]\n" + String.join("\n", rebuilt);
            metadata.put("tocSource", "docling-table");
        } else {
            if (lines.isEmpty()) return null;
            tocText = "[目录]\n" + String.join("\n", lines);
            metadata.put("tocSource", "chunk-text");
        }
        metadata.put("chunking", "docling-toc");
        metadata.put("contentLayer", "toc");
        metadata.put("originalChunkCount", buffer.size());
        metadata.remove("tokenCount");
        if (sourceTokens > 0) metadata.put("sourceTokenCount", sourceTokens);
        if (!pages.isEmpty()) {
            metadata.put("pageNumber", pages.iterator().next());
            metadata.put("pageNumbers", pages);
        }
        return Document.builder().text(tocText).metadata(metadata).build();
    }

    /**
     * Docling 的 TOC 表格在 triplet 文本里会串行丢失内容；table_cells 仍保持阅读顺序，
     * 因此优先从转换后的 JSON 表格单元重建目录。
     */
    private List<String> reconstructTocEntries(DoclingChunkResponse response, List<Document> buffer) {
        if (response == null || response.documents().isEmpty()) return List.of();
        Map<String, Object> jsonContent = null;
        for (DoclingDocumentResult document : response.documents()) {
            Object candidate = document.content() == null ? null : document.content().get("json_content");
            if (candidate instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                jsonContent = typed;
                break;
            }
        }
        if (jsonContent == null) return List.of();
        Object tablesValue = jsonContent.get("tables");
        if (!(tablesValue instanceof List<?> tables)) return List.of();
        LinkedHashSet<Integer> tableIndexes = tocTableIndexes(buffer);
        if (tableIndexes.isEmpty()) return List.of();
        List<String> cells = new ArrayList<>();
        for (Integer index : tableIndexes) {
            if (index < 0 || index >= tables.size()) continue;
            Object tableValue = tables.get(index);
            if (!(tableValue instanceof Map<?, ?> table)) continue;
            Object dataValue = table.get("data");
            if (!(dataValue instanceof Map<?, ?> data)) continue;
            Object cellsValue = data.get("table_cells");
            if (!(cellsValue instanceof List<?> tableCells)) continue;
            for (Object cellValue : tableCells) {
                if (!(cellValue instanceof Map<?, ?> cell)) continue;
                Object text = cell.get("text");
                if (text != null) cells.add(String.valueOf(text));
            }
        }
        LinkedHashSet<String> entries = new LinkedHashSet<>(buildTocEntries(cells));
        appendReferencedTocTexts(jsonContent, buffer, entries);
        return List.copyOf(entries);
    }

    /** 目录可能跨出表格（例如“参考文献”），补上 TOC 切片引用到的文本条目。 */
    private void appendReferencedTocTexts(Map<String, Object> jsonContent, List<Document> buffer,
                                          LinkedHashSet<String> entries) {
        Object textsValue = jsonContent.get("texts");
        if (!(textsValue instanceof List<?> texts)) return;
        for (Document chunk : buffer) {
            Object items = chunk.getMetadata().get("docItems");
            if (!(items instanceof java.util.Collection<?> collection)) continue;
            for (Object item : collection) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("#/texts/(\\d+)").matcher(String.valueOf(item));
                if (!matcher.find()) continue;
                int index = Integer.parseInt(matcher.group(1));
                if (index < 0 || index >= texts.size()) continue;
                Object textItem = texts.get(index);
                if (!(textItem instanceof Map<?, ?> itemMap)) continue;
                Object text = itemMap.get("text");
                if (text == null || !DOT_LEADER.matcher(String.valueOf(text)).find()) continue;
                String cleaned = cleanTocCell(String.valueOf(text));
                if (!cleaned.isEmpty() && endsWithPageRef(cleaned)) entries.add(cleaned);
            }
        }
    }

    private LinkedHashSet<Integer> tocTableIndexes(List<Document> buffer) {
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        for (Document chunk : buffer) {
            Object items = chunk.getMetadata().get("docItems");
            if (!(items instanceof java.util.Collection<?> collection)) continue;
            for (Object item : collection) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("#/tables/(\\d+)").matcher(String.valueOf(item));
                if (matcher.find()) indexes.add(Integer.parseInt(matcher.group(1)));
            }
        }
        return indexes;
    }

    private List<String> buildTocEntries(List<String> cells) {
        List<String> expanded = new ArrayList<>();
        for (String raw : cells) {
            String cleaned = cleanTocCell(raw);
            if (cleaned.isEmpty()) continue;
            for (String part : cleaned.split("(?=第\\s*[一二三四五六七八九十百千零〇]+\\s*[章节条])")) {
                if (!part.isBlank()) expanded.add(part.trim());
            }
        }
        boolean chineseNumbered = expanded.stream().anyMatch(part -> CHAPTER_MARKER.matcher(part).find());
        List<String> entries = new ArrayList<>();
        StringBuilder current = null;
        boolean hasPage = false;
        for (String cell : expanded) {
            if (current == null) {
                if (isTocEntryLabel(cell, chineseNumbered)) {
                    current = new StringBuilder(cell);
                    hasPage = endsWithPageRef(cell);
                }
                continue;
            }
            if (isPageRef(cell)) {
                if (!hasPage) {
                    current.append(' ').append(cell);
                    hasPage = true;
                    continue;
                }
                if (chineseNumbered) continue;
            }
            if (isTocEntryLabel(cell, chineseNumbered)) {
                entries.add(current.toString().trim());
                current = new StringBuilder(cell);
                hasPage = endsWithPageRef(cell);
                continue;
            }
            current.append(' ').append(cell);
            if (endsWithPageRef(cell)) hasPage = true;
        }
        if (current != null) entries.add(current.toString().trim());
        return entries.stream().filter(line -> !line.isBlank()).distinct().toList();
    }

    private String cleanTocCell(String value) {
        String cleaned = DOT_LEADER.matcher(value == null ? "" : value).replaceAll(" ")
                .replaceAll("[\\t ]{2,}", " ")
                .trim();
        return cleaned.replaceAll("第\\s+([一二三四五六七八九十百千零〇]+\\s*[章节条])", "第$1");
    }

    private boolean isTocEntryLabel(String value, boolean chineseNumbered) {
        if (value.equals("前言") || value.equals("参考文献") || value.equals("目次")) return true;
        if (!chineseNumbered) {
            if (value.matches("\\d+(?:\\.\\d+)*")) return true;
            if (value.matches("\\d+(?:\\.\\d+)*\\s+.+")) return true;
        }
        if (CHAPTER_MARKER.matcher(value).find()) return true;
        return value.matches("附录[A-Za-z0-9]+（(资料性|规范性)）.*");
    }

    private boolean isPageRef(String value) {
        return value.matches("(?i)\\d+|[ivxlcdm]+");
    }

    private boolean endsWithPageRef(String value) {
        return value.matches("(?i).*\\s(?:\\d+|[ivxlcdm]+)$");
    }

    private boolean isTocChunk(Document document) {
        Object sectionPath = document.getMetadata().get("sectionPath");
        if (sectionPath != null && isTocHeading(String.valueOf(sectionPath))) return true;
        String text = document.getText();
        if (text == null) return false;
        int dottedLines = 0;
        for (String line : text.split("\n")) {
            if (TOC_PAGE_REF.matcher(line).matches()) dottedLines++;
        }
        return dottedLines >= 2;
    }

    private boolean isTocHeading(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
        if (compact.isEmpty()) return false;
        if (compact.equals("目次") || compact.equals("目录")
                || compact.equals("contents") || compact.equals("tableofcontents")) return true;
        return compact.endsWith(">目次") || compact.endsWith(">目录")
                || compact.endsWith(">contents") || compact.endsWith(">tableofcontents");
    }

    private String resumeSection(Map<String, Object> metadata, String text) {
        String value = (String.valueOf(metadata.getOrDefault("sectionPath", "")) + " "
                + (text == null ? "" : text)).toLowerCase(java.util.Locale.ROOT);
        if (contains(value, "基本信息", "个人信息", "联系方式", "求职意向", "电话", "邮箱", "姓名")) return "基本信息";
        if (contains(value, "工作经历", "工作经验", "职业经历", "任职", "公司")) return "工作经历";
        if (contains(value, "项目经历", "项目经验", "项目")) return "项目经历";
        if (contains(value, "教育经历", "教育", "学校", "学历")) return "教育经历";
        if (contains(value, "专业技能", "技能", "技术栈", "专业能力")) return "专业技能";
        return "其他";
    }

    private boolean contains(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) return true;
        }
        return false;
    }

    private enum TocHandling {
        MERGE, DROP, KEEP;

        private static TocHandling from(String value) {
            if (value == null) return MERGE;
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "drop" -> DROP;
                case "keep" -> KEEP;
                default -> MERGE;
            };
        }
    }
}
