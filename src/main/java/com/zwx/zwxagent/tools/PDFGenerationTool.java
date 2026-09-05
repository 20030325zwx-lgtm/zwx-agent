package com.zwx.zwxagent.tools;

import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class PDFGenerationTool {

    private final ToolSandbox sandbox;
    private final Path workDir;
    private final Path pdfDir;

    public PDFGenerationTool(ToolSandbox sandbox, Path workDir) {
        this.sandbox = sandbox;
        this.workDir = workDir;
        this.pdfDir = workDir.resolve("pdf");
    }

    @Tool(description = "Generate a PDF file with given content", returnDirect = false)
    public String generatePDF(
            @ToolParam(description = "Name of the file to save the generated PDF, relative to the task workspace") String fileName,
            @ToolParam(description = "Content to be included in the PDF") String content) {
        Path filePath = null;
        try {
            filePath = sandbox.resolveWithin(pdfDir, fileName);
            String fontPath = System.getenv("APP_PDF_FONT_PATH");
            if (fontPath == null || fontPath.isBlank()) {
                String macOsFontPath = "/Library/Fonts/Arial Unicode.ttf";
                fontPath = new File(macOsFontPath).isFile() ? macOsFontPath : null;
            }
            if (fontPath == null || fontPath.isBlank() || !new File(fontPath).isFile()) {
                return "Error generating PDF: no usable Chinese font. Set APP_PDF_FONT_PATH to a .ttf, .otf, or .ttc font file.";
            }
            PdfFont font = PdfFontFactory.createFont(fontPath, PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
            Files.createDirectories(filePath.getParent());
            try (PdfWriter writer = new PdfWriter(filePath.toString());
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {
                document.setFont(font);
                Paragraph paragraph = new Paragraph(content);
                document.add(paragraph);
            }
            return "PDF generated successfully to: " + workDir.relativize(filePath);
        } catch (IOException | IllegalArgumentException exception) {
            if (filePath != null) {
                try {
                    Files.deleteIfExists(filePath);
                } catch (IOException ignored) {
                }
            }
            return "Error generating PDF: " + exception.getMessage();
        }
    }
}
