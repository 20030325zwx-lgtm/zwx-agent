package com.zwx.zwxagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 自定义基于 Token 的切词器
 */
@Component
class MyTokenTextSplitter {
    private static final int STRUCTURED_MAX_CHARS = 1200;
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
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
        String[] lines = source.getText().replace("\r\n", "\n").split("\n", -1);
        for (String line : lines) {
            var heading = MARKDOWN_HEADING.matcher(line);
            if (heading.matches()) {
                flushBlock(chunks, source, headings, block);
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
        flushBlock(chunks, source, headings, block);
        return chunks;
    }

    private void flushBlock(List<Document> chunks, Document source, List<String> headings, StringBuilder block) {
        if (block.isEmpty()) return;
        String content = block.toString().trim();
        block.setLength(0);
        if (content.isBlank()) return;
        for (String part : splitBlock(content)) {
            Map<String, Object> metadata = new HashMap<>(source.getMetadata());
            String sectionPath = headings.stream().filter(value -> !value.isBlank()).reduce((left, right) -> left + " > " + right).orElse("");
            if (!sectionPath.isBlank()) metadata.put("sectionPath", sectionPath);
            metadata.put("chunking", "structured-markdown");
            String contextualContent = sectionPath.isBlank() ? part : "[章节] " + sectionPath + "\n\n" + part;
            chunks.add(Document.builder().text(contextualContent).metadata(metadata).build());
        }
    }

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
