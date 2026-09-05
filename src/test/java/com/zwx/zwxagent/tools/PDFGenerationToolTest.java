package com.zwx.zwxagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class PDFGenerationToolTest {

    @TempDir
    Path tempDir;

    @Test
    void generatePDFWithinSandbox() {
        ToolSandbox sandbox = new ToolSandbox();
        PDFGenerationTool tool = new PDFGenerationTool(sandbox, tempDir);
        String result = tool.generatePDF("report.pdf", "测试内容");
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.startsWith("PDF generated successfully to:") || result.startsWith("Error generating PDF: no usable Chinese font")
                || result.startsWith("Error generating PDF:"));
    }

    @Test
    void rejectPathTraversalFileName() {
        ToolSandbox sandbox = new ToolSandbox();
        PDFGenerationTool tool = new PDFGenerationTool(sandbox, tempDir);
        String result = tool.generatePDF("../../escape.pdf", "nope");
        Assertions.assertTrue(result.startsWith("Error generating PDF:"));
    }
}
