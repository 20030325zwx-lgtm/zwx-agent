package com.zwx.zwxagent.capability.tools;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** P1 交付物生成：xlsx 工具的最小回归（不依赖数据库/模型）。 */
class SpreadsheetGenerationToolTest {

    @TempDir
    Path workDir;

    private SpreadsheetGenerationTool tool() {
        return new SpreadsheetGenerationTool(new ToolSandbox(), workDir);
    }

    @Test
    void writesWorkbookWithHeaderAndNumericCells() throws Exception {
        String result = tool().generateSpreadsheet("reports/sales.xlsx", "销售",
                "[\"城市\",\"销售额\"]", "[[\"北京\",120],[\"上海\",90.5]]");

        assertThat(result).doesNotContain("Error");
        Path file = workDir.resolve("reports/sales.xlsx");
        assertThat(Files.exists(file)).isTrue();
        try (InputStream in = Files.newInputStream(file); Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheet("销售");
            assertThat(sheet).isNotNull();
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("城市");
            assertThat(sheet.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(120d);
            assertThat(sheet.getRow(2).getCell(1).getNumericCellValue()).isEqualTo(90.5d);
        }
    }

    @Test
    void rejectsNonXlsxName() {
        assertThat(tool().generateSpreadsheet("data.csv", "", "[]", "[]")).contains("must end with .xlsx");
    }

    @Test
    void rejectsPathTraversal() {
        assertThat(tool().generateSpreadsheet("../escape.xlsx", "", "[]", "[]"))
                .contains("Error");
    }

    @Test
    void rejectsInvalidJson() {
        assertThat(tool().generateSpreadsheet("data.xlsx", "", "\"a\"", "[]"))
                .contains("headers must be a JSON array");
    }
}
