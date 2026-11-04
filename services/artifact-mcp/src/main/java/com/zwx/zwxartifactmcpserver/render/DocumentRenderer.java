package com.zwx.zwxartifactmcpserver.render;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown-lite → docx。支持：标题（#/##/###）、无序列表（-/*）、有序列表（1.）、
 * 表格（| a | b |，第二行 --- 分隔）、段落、行内粗体（**x**）、空行分页留白。
 * 刻意不做完整 Markdown：目标是稳定、可预测的公文/报告排版。
 */
@Service
public class DocumentRenderer {

    public byte[] render(String title, String markdown) throws IOException {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (title != null && !title.isBlank()) {
                XWPFParagraph heading = document.createParagraph();
                heading.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun run = heading.createRun();
                run.setText(title.trim());
                run.setBold(true);
                run.setFontSize(20);
            }
            List<String> lines = markdown == null ? List.of() : List.of(markdown.split("\n", -1));
            int index = 0;
            while (index < lines.size()) {
                String line = lines.get(index).trim();
                if (line.isEmpty()) {
                    index += 1;
                    continue;
                }
                if (line.startsWith("|")) {
                    int tableEnd = index;
                    while (tableEnd < lines.size() && lines.get(tableEnd).trim().startsWith("|")) {
                        tableEnd += 1;
                    }
                    addTable(document, lines.subList(index, tableEnd));
                    index = tableEnd;
                    continue;
                }
                if (line.startsWith("#")) {
                    int level = headingLevel(line);
                    String text = line.substring(Math.min(level, line.length())).trim();
                    addHeading(document, text, level);
                } else if (line.startsWith("- ") || line.startsWith("* ")) {
                    addBullet(document, line.substring(2).trim());
                } else if (line.matches("^\\d+[.)]\\s+.*")) {
                    String text = line.replaceFirst("^\\d+[.)]\\s+", "");
                    addNumbered(document, text);
                } else {
                    addParagraph(document, line);
                }
                index += 1;
            }
            document.write(out);
            return out.toByteArray();
        }
    }

    /** 模板占位符 {{key}} 替换（整段文本级；占位符跨 run 时不保证命中，P3 再处理）。 */
    public byte[] fillTemplate(byte[] template, java.util.Map<String, String> values) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new java.io.ByteArrayInputStream(template));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            replaceInParagraphs(document.getParagraphs(), values);
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : row.getTableCells()) {
                        replaceInParagraphs(cell.getParagraphs(), values);
                    }
                }
            }
            document.write(out);
            return out.toByteArray();
        }
    }

    private void replaceInParagraphs(List<XWPFParagraph> paragraphs, java.util.Map<String, String> values) {
        for (XWPFParagraph paragraph : paragraphs) {
            for (XWPFRun run : paragraph.getRuns()) {
                String text = run.getText(0);
                if (text == null) continue;
                String replaced = text;
                for (java.util.Map.Entry<String, String> entry : values.entrySet()) {
                    replaced = replaced.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
                }
                if (!replaced.equals(text)) run.setText(replaced, 0);
            }
        }
    }

    private int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') level += 1;
        return Math.max(1, Math.min(level, 3));
    }

    private void addHeading(XWPFDocument document, String text, int level) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(200);
        paragraph.setSpacingAfter(80);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(level == 1 ? 18 : level == 2 ? 15 : 13);
    }

    private void addBullet(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(360);
        XWPFRun run = paragraph.createRun();
        run.setText("• " + text);
        run.setFontSize(11);
    }

    private void addNumbered(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(360);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setFontSize(11);
    }

    private void addParagraph(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(80);
        appendBoldRuns(paragraph, text);
    }

    /** 行内 **粗体** 解析：按 ** 分段，奇数段加粗。 */
    private void appendBoldRuns(XWPFParagraph paragraph, String text) {
        String[] parts = text.split("\\*\\*", -1);
        for (int i = 0; i < parts.length; i += 1) {
            if (parts[i].isEmpty()) continue;
            XWPFRun run = paragraph.createRun();
            run.setText(parts[i]);
            run.setFontSize(11);
            if (i % 2 == 1) run.setBold(true);
        }
    }

    private void addTable(XWPFDocument document, List<String> block) {
        List<List<String>> rows = new ArrayList<>();
        for (String raw : block) {
            String line = raw.trim();
            if (line.matches("^\\|[\\s:|-]+\\|$")) continue;
            List<String> cells = new ArrayList<>();
            for (String cell : line.split("\\|", -1)) {
                String value = cell.trim();
                if (!value.isEmpty() || cells.size() > 0) cells.add(value);
            }
            if (!cells.isEmpty() && cells.get(0).isEmpty()) cells.remove(0);
            if (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) cells.remove(cells.size() - 1);
            if (!cells.isEmpty()) rows.add(cells);
        }
        if (rows.isEmpty()) return;
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        XWPFTable table = document.createTable(rows.size(), columns);
        for (int r = 0; r < rows.size(); r += 1) {
            XWPFTableRow row = table.getRow(r);
            List<String> cells = rows.get(r);
            for (int c = 0; c < columns; c += 1) {
                String value = c < cells.size() ? cells.get(c) : "";
                row.getCell(c).setText(value);
                if (r == 0) {
                    row.getCell(c).getParagraphs().get(0).getRuns().forEach(run -> run.setBold(true));
                }
            }
        }
        document.createParagraph();
    }
}
