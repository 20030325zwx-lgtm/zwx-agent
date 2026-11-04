package com.zwx.zwxagent.capability.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 交付物生成：把结构化数据写成 .xlsx，落在当前任务工作区里。
 *
 * <p>P1 先内置在 backend（POI），产物由前端「产物卡片」展示并走既有签名下载；
 * P2 计划整体迁移到 services/artifact-mcp，与 docx/pptx/格式转换一起托管。</p>
 */
public class SpreadsheetGenerationTool {

    private static final Logger log = LoggerFactory.getLogger(SpreadsheetGenerationTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ROWS = 5000;
    private static final int MAX_COLUMNS = 64;
    private static final int MAX_CELL_CHARS = 32000;

    private final ToolSandbox sandbox;
    private final Path workDir;

    public SpreadsheetGenerationTool(ToolSandbox sandbox, Path workDir) {
        this.sandbox = sandbox;
        this.workDir = workDir;
    }

    @Tool(description = """
            Generate an Excel .xlsx spreadsheet in the current task workspace and return its path, byte size and row count.
            Use this for tabular deliverables (reports, exported query results, comparison tables).
            Arguments: fileName must end with .xlsx; headers is a JSON array of column titles like ["城市","销售额"];
            rows is a JSON array of row arrays like [["北京",120],["上海",90]] (numbers stay numeric, strings stay text).""")
    public String generateSpreadsheet(
            @ToolParam(description = "Destination file name relative to the task workspace, must end with .xlsx, e.g. sales.xlsx") String fileName,
            @ToolParam(description = "Sheet name, optional; defaults to Sheet1") String sheetName,
            @ToolParam(description = "JSON array of header strings, e.g. [\"城市\",\"销售额\"]") String headers,
            @ToolParam(description = "JSON array of row arrays, e.g. [[\"北京\",120],[\"上海\",90]]") String rows) {
        if (fileName == null || fileName.isBlank()) {
            return error("fileName is required");
        }
        String trimmedName = fileName.trim();
        if (!trimmedName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            return error("fileName must end with .xlsx");
        }
        List<String> headerList;
        List<List<Object>> rowList;
        try {
            headerList = parseHeaders(headers);
            rowList = parseRows(rows, headerList.size());
        } catch (IllegalArgumentException exception) {
            return error(exception.getMessage());
        }
        Path target;
        try {
            target = sandbox.resolveWithin(workDir, trimmedName);
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IllegalArgumentException | IOException exception) {
            return error("Invalid file path: " + exception.getMessage());
        }
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(resolveSheetName(sheetName));
            CellStyle headerStyle = headerStyle(workbook);
            int rowIndex = 0;
            if (!headerList.isEmpty()) {
                Row headerRow = sheet.createRow(rowIndex++);
                for (int column = 0; column < headerList.size(); column += 1) {
                    Cell cell = headerRow.createCell(column);
                    cell.setCellValue(headerList.get(column));
                    cell.setCellStyle(headerStyle);
                }
            }
            for (List<Object> values : rowList) {
                Row row = sheet.createRow(rowIndex++);
                for (int column = 0; column < values.size() && column < MAX_COLUMNS; column += 1) {
                    writeCell(row.createCell(column), values.get(column));
                }
            }
            if (!headerList.isEmpty()) sheet.createFreezePane(0, 1);
            autosize(sheet, Math.max(headerList.size(), rowList.stream().mapToInt(List::size).max().orElse(0)));
            try (OutputStream out = Files.newOutputStream(target)) {
                workbook.write(out);
            }
            long bytes = Files.size(target);
            log.info("Generated spreadsheet {} ({} rows, {} bytes)", trimmedName, rowList.size(), bytes);
            return JSON.createObjectNode()
                    .put("file", trimmedName)
                    .put("bytes", bytes)
                    .put("rows", rowList.size())
                    .put("columns", Math.max(headerList.size(), rowList.stream().mapToInt(List::size).max().orElse(0)))
                    .toString();
        } catch (IOException exception) {
            log.warn("Unable to generate spreadsheet {}: {}", trimmedName, exception.getMessage());
            return error("Unable to write spreadsheet: " + exception.getMessage());
        }
    }

    private List<String> parseHeaders(String headers) {
        if (headers == null || headers.isBlank()) return List.of();
        JsonNode node = readJson(headers, "headers");
        if (!node.isArray()) throw new IllegalArgumentException("headers must be a JSON array of strings");
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (result.size() >= MAX_COLUMNS) break;
            result.add(cellText(item));
        }
        return result;
    }

    private List<List<Object>> parseRows(String rows, int headerColumns) {
        if (rows == null || rows.isBlank()) return List.of();
        JsonNode node = readJson(rows, "rows");
        if (!node.isArray()) throw new IllegalArgumentException("rows must be a JSON array of row arrays");
        if (node.size() > MAX_ROWS) {
            throw new IllegalArgumentException("rows exceeds the limit of " + MAX_ROWS + " rows");
        }
        List<List<Object>> result = new ArrayList<>(node.size());
        for (JsonNode line : node) {
            if (!line.isArray()) throw new IllegalArgumentException("each row must be a JSON array of cell values");
            List<Object> values = new ArrayList<>(line.size());
            for (JsonNode cell : line) {
                if (values.size() >= MAX_COLUMNS) break;
                if (cell.isNumber()) values.add(cell.numberValue());
                else if (cell.isBoolean()) values.add(cell.booleanValue());
                else if (cell.isNull()) values.add(null);
                else values.add(cellText(cell));
            }
            if (headerColumns > 0 && values.size() > headerColumns) {
                values = new ArrayList<>(values.subList(0, headerColumns));
            }
            result.add(values);
        }
        return result;
    }

    private JsonNode readJson(String raw, String field) {
        try {
            return JSON.readTree(raw);
        } catch (IOException exception) {
            throw new IllegalArgumentException(field + " is not valid JSON");
        }
    }

    private String cellText(JsonNode node) {
        String text = node.isValueNode() ? node.asText("") : node.toString();
        return text.length() <= MAX_CELL_CHARS ? text : text.substring(0, MAX_CELL_CHARS);
    }

    private void writeCell(Cell cell, Object value) {
        if (value == null) return;
        if (value instanceof Number number) cell.setCellValue(number.doubleValue());
        else if (value instanceof Boolean flag) cell.setCellValue(flag);
        else cell.setCellValue(String.valueOf(value));
    }

    private String resolveSheetName(String sheetName) {
        String name = sheetName == null || sheetName.isBlank() ? "Sheet1" : sheetName.trim();
        if (name.length() > 31) name = name.substring(0, 31);
        try {
            return WorkbookUtil.createSafeSheetName(name);
        } catch (RuntimeException exception) {
            return "Sheet1";
        }
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private void autosize(Sheet sheet, int columns) {
        for (int column = 0; column < columns && column < MAX_COLUMNS; column += 1) {
            sheet.autoSizeColumn(column);
            int width = sheet.getColumnWidth(column);
            // 上限约 60 字符宽，避免整段长文本把列撑爆
            if (width > 60 * 256) sheet.setColumnWidth(column, 60 * 256);
        }
    }

    private String error(String message) {
        return "Error: " + message;
    }
}
