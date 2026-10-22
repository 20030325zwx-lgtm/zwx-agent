package com.zwx.zwxagent.knowledge.document;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 自定义基于 Token 的切词器
 */
@Component
class MyTokenTextSplitter {
    private static final int STRUCTURED_MAX_CHARS = 1200;
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern PDF_PAGE = Pattern.compile("^\\[PDF 第 (\\d+) 页]\\s*$");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？；.!?;])\\s+");

    public List<Document> splitDocuments(List<Document> documents) {
        TokenTextSplitter splitter = new TokenTextSplitter();
        return splitter.apply(documents);
    }

    public List<Document> splitCustomized(List<Document> documents) {
        TokenTextSplitter splitter = new TokenTextSplitter(200, 100, 10, 5000, true);
        return splitter.apply(documents);
    }

    /**
     * Markdown-aware splitting for private knowledge. Headings, paragraphs, lists and tables
     * are kept intact whenever possible; long blocks are split only at sentence boundaries.
     */
    public List<Document> splitStructuredMarkdown(List<Document> documents) {
        List<Document> chunks = new ArrayList<>();
        for (Document source : documents) chunks.addAll(splitStructuredDocument(source));
        return chunks;
    }

    private List<Document> splitStructuredDocument(Document source) {
        List<Document> chunks = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        Integer pageNumber = null;
        String[] lines = normalizeHtmlTables(source.getText()).replace("\r\n", "\n").split("\n", -1);
        for (String line : lines) {
            var page = PDF_PAGE.matcher(line.trim());
            if (page.matches()) {
                flushBlock(chunks, source, headings, block, pageNumber);
                pageNumber = Integer.parseInt(page.group(1));
                continue;
            }
            var heading = MARKDOWN_HEADING.matcher(line);
            if (heading.matches()) {
                flushBlock(chunks, source, headings, block, pageNumber);
                int level = heading.group(1).length();
                while (headings.size() < level) headings.add("");
                headings.set(level - 1, heading.group(2));
                while (headings.size() > level) headings.remove(headings.size() - 1);
                continue;
            }
            if (line.isBlank()) {
                if (block.length() > 0 && !block.toString().endsWith("\n\n")) block.append("\n\n");
            } else {
                if (block.length() > 0 && !block.toString().endsWith("\n")) block.append('\n');
                block.append(line);
            }
        }
        flushBlock(chunks, source, headings, block, pageNumber);
        return chunks;
    }

    private void flushBlock(List<Document> chunks, Document source, List<String> headings, StringBuilder block, Integer pageNumber) {
        if (block.isEmpty()) return;
        String content = block.toString().trim();
        block.setLength(0);
        if (content.isBlank()) return;
        for (String part : splitBlock(content)) {
            Map<String, Object> metadata = new HashMap<>(source.getMetadata());
            String sectionPath = headings.stream().filter(value -> !value.isBlank()).reduce((left, right) -> left + " > " + right).orElse("");
            if (!sectionPath.isBlank()) metadata.put("sectionPath", sectionPath);
            if (pageNumber != null) metadata.put("pageNumber", pageNumber);
            metadata.put("chunking", "structured-markdown");
            if (part.contains("[表格记录]")) metadata.put("structuredContentType", "table-record");
            String contextualContent = sectionPath.isBlank() ? part : "[章节] " + sectionPath + "\n\n" + part;
            chunks.add(Document.builder().text(contextualContent).metadata(metadata).build());
        }
    }

    private String normalizeHtmlTables(String content) {
        if (content == null || !content.toLowerCase(java.util.Locale.ROOT).contains("<table")) return content;
        org.jsoup.nodes.Document parsed = Parser.htmlParser().parseInput(content, "");
        StringBuilder normalized = new StringBuilder();
        for (Node node : parsed.body().childNodes()) appendNodeText(node, normalized);
        return normalized.toString();
    }

    private void appendNodeText(Node node, StringBuilder output) {
        if (node instanceof Element element && element.normalName().equals("table")) {
            appendBlockSeparator(output);
            output.append(normalizeHtmlTable(element)).append("\n\n");
            return;
        }
        if (node instanceof TextNode textNode) {
            output.append(textNode.getWholeText());
            return;
        }
        if (node instanceof Element element && element.normalName().equals("br")) {
            output.append('\n');
            return;
        }
        boolean blockElement = node instanceof Element element
                && Set.of("article", "blockquote", "div", "li", "ol", "p", "section", "ul").contains(element.normalName());
        if (blockElement) appendBlockSeparator(output);
        for (Node child : node.childNodes()) appendNodeText(child, output);
        if (blockElement) appendBlockSeparator(output);
    }

    private void appendBlockSeparator(StringBuilder output) {
        if (output.isEmpty()) return;
        if (!output.toString().endsWith("\n\n")) {
            if (!output.toString().endsWith("\n")) output.append('\n');
            output.append('\n');
        }
    }

    private String normalizeHtmlTable(Element table) {
        List<Element> rows = table.select("> thead > tr, > tbody > tr, > tfoot > tr, > tr");
        if (rows.isEmpty()) return table.text();

        List<Map<Integer, String>> grid = new ArrayList<>();
        Map<Integer, SpanCell> carriedCells = new LinkedHashMap<>();
        int columnCount = 0;
        for (Element row : rows) {
            Map<Integer, String> cells = new LinkedHashMap<>();
            carriedCells.forEach((column, cell) -> cells.put(column, cell.text()));
            Map<Integer, SpanCell> nextCarriedCells = new LinkedHashMap<>();
            carriedCells.forEach((column, cell) -> {
                if (cell.rowsRemaining() > 1) nextCarriedCells.put(column, new SpanCell(cell.text(), cell.rowsRemaining() - 1));
            });

            int column = 0;
            for (Element cell : row.select("> th, > td")) {
                int colspan = span(cell, "colspan");
                while (hasOccupiedColumn(cells, column, colspan)) column++;
                String value = cell.text().replaceAll("\\s+", " ").trim();
                for (int offset = 0; offset < colspan; offset++) {
                    cells.put(column + offset, value);
                    int rowspan = span(cell, "rowspan");
                    if (rowspan > 1) nextCarriedCells.put(column + offset, new SpanCell(value, rowspan - 1));
                }
                column += colspan;
            }
            if (!cells.isEmpty()) columnCount = Math.max(columnCount, cells.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1);
            grid.add(cells);
            carriedCells = nextCarriedCells;
        }

        int headerRows = table.select("> thead > tr").size();
        if (headerRows == 0) headerRows = 1;
        headerRows = Math.min(headerRows, grid.size());
        List<String> headers = new ArrayList<>();
        for (int column = 0; column < columnCount; column++) {
            List<String> labels = new ArrayList<>();
            for (int row = 0; row < headerRows; row++) {
                String label = grid.get(row).getOrDefault(column, "");
                if (!label.isBlank() && !labels.contains(label)) labels.add(label);
            }
            headers.add(labels.isEmpty() ? "第" + (column + 1) + "列" : String.join(" / ", labels));
        }

        List<String> records = new ArrayList<>();
        for (int row = headerRows; row < grid.size(); row++) {
            Map<Integer, String> cells = grid.get(row);
            if (cells.values().stream().allMatch(String::isBlank)) continue;
            List<String> fields = new ArrayList<>();
            for (int column = 0; column < columnCount; column++) {
                String value = cells.getOrDefault(column, "");
                if (!value.isBlank()) fields.add(headers.get(column) + "：" + value);
            }
            if (!fields.isEmpty()) records.add("[表格记录] " + String.join("；", fields));
        }
        return records.isEmpty() ? table.text() : String.join("\n\n", records);
    }

    private boolean hasOccupiedColumn(Map<Integer, String> cells, int start, int span) {
        for (int column = start; column < start + span; column++) {
            if (cells.containsKey(column)) return true;
        }
        return false;
    }

    private int span(Element cell, String attribute) {
        try {
            return Math.max(1, Integer.parseInt(cell.attr(attribute)));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private record SpanCell(String text, int rowsRemaining) {}

    private List<String> splitBlock(String block) {
        if (block.length() <= STRUCTURED_MAX_CHARS) return List.of(block);
        String[] paragraphs = block.split("\\n\\s*\\n");
        if (paragraphs.length > 1) {
            List<String> parts = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String paragraph : paragraphs) {
                for (String unit : splitAtomicUnit(paragraph.trim())) {
                    if (current.length() > 0 && current.length() + unit.length() + 2 > STRUCTURED_MAX_CHARS) {
                        parts.add(current.toString().trim());
                        current.setLength(0);
                    }
                    if (current.length() > 0) current.append("\n\n");
                    current.append(unit);
                }
            }
            if (current.length() > 0) parts.add(current.toString().trim());
            return parts;
        }
        return splitAtomicUnit(block);
    }

    private List<String> splitAtomicUnit(String block) {
        if (block.isBlank() || block.length() <= STRUCTURED_MAX_CHARS) return block.isBlank() ? List.of() : List.of(block);
        if (isTable(block)) return splitLines(block, true);
        if (isList(block)) return splitLines(block, false);
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : SENTENCE_END.split(block)) {
            if (current.length() > 0 && current.length() + sentence.length() + 1 > STRUCTURED_MAX_CHARS) {
                parts.add(current.toString().trim());
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(sentence);
        }
        if (current.length() > 0) parts.add(current.toString().trim());
        return parts.isEmpty() ? splitLines(block, false) : parts;
    }

    private List<String> splitLines(String block, boolean repeatTableHeader) {
        String[] lines = block.split("\\n");
        List<String> parts = new ArrayList<>();
        String header = repeatTableHeader && lines.length > 0 ? lines[0] + (lines.length > 1 ? "\n" + lines[1] : "") : "";
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            if (repeatTableHeader && index < 2) {
                current.append(lines[index]).append('\n');
                continue;
            }
            String line = lines[index];
            if (current.length() > 0 && current.length() + line.length() + 1 > STRUCTURED_MAX_CHARS) {
                parts.add(current.toString().trim());
                current.setLength(0);
                if (repeatTableHeader) current.append(header).append('\n');
            }
            if (current.length() > 0) current.append('\n');
            current.append(line);
        }
        if (current.length() > 0) parts.add(current.toString().trim());
        return parts;
    }

    private boolean isTable(String block) {
        String[] lines = block.split("\\n");
        return lines.length >= 2 && lines[0].contains("|") && lines[1].matches(".*\\|\\s*:?-{3,}.*");
    }

    private boolean isList(String block) {
        return block.lines().allMatch(line -> line.matches("\\s*(?:[-*+] |\\d+[.)] ).*"));
    }
}
