package com.zwx.zwxagent.knowledge.document;

import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 HTML/Markdown 表格解析为稳定的 header + rows 结构。
 * 引用预览和切片文本共用同一份解析结果，避免两边行号错位。
 */
public final class HtmlTableParser {

    private HtmlTableParser() {
    }

    public record TableData(String caption, List<String> headers, List<List<String>> rows) {

        public TableData {
            headers = headers == null ? List.of() : List.copyOf(headers);
            rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList();
        }

        public boolean isEmpty() {
            return rows.isEmpty();
        }

        public String headerLine() {
            return String.join(" | ", headers);
        }

        public String recordText(int rowIndex) {
            if (rowIndex < 0 || rowIndex >= rows.size()) return "";
            List<String> fields = new ArrayList<>();
            List<String> row = rows.get(rowIndex);
            for (int column = 0; column < Math.min(headers.size(), row.size()); column++) {
                String value = row.get(column);
                if (value == null || value.isBlank()) continue;
                fields.add(headers.get(column) + "：" + value);
            }
            return fields.isEmpty() ? "" : "[表格记录] " + String.join("；", fields);
        }

        public String recordBlock(int rowStart, int rowEnd) {
            List<String> records = new ArrayList<>();
            for (int index = Math.max(0, rowStart); index < Math.min(rowEnd, rows.size()); index++) {
                String record = recordText(index);
                if (!record.isBlank()) records.add(record);
            }
            return String.join("\n", records);
        }
    }

    public static TableData parse(String html, String caption) {
        if (html == null || html.isBlank()) return new TableData(caption, List.of(), List.of());
        Element table = Parser.htmlParser().parseInput(html, "").selectFirst("table");
        if (table == null) return new TableData(caption, List.of(), List.of());
        return parse(table, caption);
    }

    public static TableData parse(Element table, String caption) {
        if (table == null) return new TableData(caption, List.of(), List.of());
        List<Element> rows = table.select("> thead > tr, > tbody > tr, > tfoot > tr, > tr");
        if (rows.isEmpty()) return new TableData(caption, List.of(), List.of());

        List<Map<Integer, String>> grid = new ArrayList<>();
        Map<Integer, SpanCell> carriedCells = new LinkedHashMap<>();
        int columnCount = 0;
        for (Element row : rows) {
            Map<Integer, String> cells = new LinkedHashMap<>();
            carriedCells.forEach((column, cell) -> cells.put(column, cell.text()));
            Map<Integer, SpanCell> nextCarriedCells = new LinkedHashMap<>();
            carriedCells.forEach((column, cell) -> {
                if (cell.rowsRemaining() > 1) {
                    nextCarriedCells.put(column, new SpanCell(cell.text(), cell.rowsRemaining() - 1));
                }
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
            if (!cells.isEmpty()) {
                columnCount = Math.max(columnCount, cells.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1);
            }
            grid.add(cells);
            carriedCells = nextCarriedCells;
        }

        int declaredHeaderRows = table.select("> thead > tr").size();
        if (declaredHeaderRows == 0) declaredHeaderRows = 1;
        int noteRows = 0;
        while (noteRows < grid.size() && noteRows < declaredHeaderRows && isNoteRow(grid.get(noteRows))) {
            noteRows++;
        }
        int headerStart = noteRows >= grid.size() ? 0 : noteRows;
        int headerRows = Math.max(1, declaredHeaderRows - noteRows);
        headerRows = Math.min(headerRows, grid.size() - headerStart);
        int dataStart = Math.min(grid.size(), headerStart + headerRows);
        List<String> headers = new ArrayList<>();
        for (int column = 0; column < columnCount; column++) {
            List<String> labels = new ArrayList<>();
            for (int row = headerStart; row < dataStart; row++) {
                String label = grid.get(row).getOrDefault(column, "");
                if (!label.isBlank() && !labels.contains(label)) labels.add(label);
            }
            String joined = labels.isEmpty() ? "第" + (column + 1) + "列" : String.join(" / ", labels);
            if (joined.length() > 120) joined = joined.substring(0, 120) + "…";
            headers.add(joined);
        }

        List<List<String>> dataRows = new ArrayList<>();
        for (int row = dataStart; row < grid.size(); row++) {
            Map<Integer, String> cells = grid.get(row);
            if (cells.values().stream().allMatch(String::isBlank)) continue;
            List<String> values = new ArrayList<>(columnCount);
            for (int column = 0; column < columnCount; column++) {
                values.add(cells.getOrDefault(column, ""));
            }
            dataRows.add(values);
        }
        return new TableData(caption, headers, dataRows);
    }

    /** 解析 GFM 管道表；不是表格时返回 null。 */
    public static TableData parseMarkdown(String block, String caption) {
        if (block == null || block.isBlank()) return null;
        String[] lines = block.replace("\r\n", "\n").split("\n", -1);
        if (lines.length < 2) return null;
        String headerLine = lines[0].trim();
        String separator = lines[1].trim();
        if (!headerLine.contains("|") || !separator.matches("\\|?\\s*:?-{3,}.*")) return null;
        List<String> headers = new ArrayList<>(cells(headerLine));
        if (headers.isEmpty()) return null;
        for (int index = 0; index < headers.size(); index++) {
            if (headers.get(index).isBlank()) {
                headers.set(index, "第" + (index + 1) + "列");
            }
        }
        List<List<String>> rows = new ArrayList<>();
        for (int index = 2; index < lines.length; index++) {
            if (!lines[index].contains("|")) continue;
            List<String> row = cells(lines[index]);
            if (row.stream().allMatch(String::isBlank)) continue;
            while (row.size() < headers.size()) row.add("");
            rows.add(row.subList(0, headers.size()));
        }
        return new TableData(caption, headers, rows);
    }

    private static List<String> cells(String line) {
        String normalized = line.trim();
        if (normalized.startsWith("|")) normalized = normalized.substring(1);
        if (normalized.endsWith("|")) normalized = normalized.substring(0, normalized.length() - 1);
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (char character : normalized.toCharArray()) {
            if (escaped) {
                if (character != '|') current.append('\\');
                current.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '|') {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (escaped) current.append('\\');
        cells.add(current.toString().trim());
        return cells;
    }

    /** 首行可能是 colspan 覆盖整列的说明/标题行，不能当作列名反复拼进每条记录。 */
    private static boolean isNoteRow(Map<Integer, String> row) {
        List<String> values = row.values().stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        return values.size() == 1 && values.get(0).length() > 40;
    }

    private static boolean hasOccupiedColumn(Map<Integer, String> cells, int start, int span) {
        for (int column = start; column < start + span; column++) {
            if (cells.containsKey(column)) return true;
        }
        return false;
    }

    private static int span(Element cell, String attribute) {
        try {
            return Math.max(1, Integer.parseInt(cell.attr(attribute)));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private record SpanCell(String text, int rowsRemaining) {
    }
}
