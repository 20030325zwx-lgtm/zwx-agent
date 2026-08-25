package com.zwx.zwxagent.tools;

import cn.hutool.core.io.FileUtil;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.zwx.zwxagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;
import java.io.IOException;

/**
 * PDF 生成工具
 */
public class PDFGenerationTool {

    @Tool(description = "Generate a PDF file with given content", returnDirect = false)
    public String generatePDF(
            @ToolParam(description = "Name of the file to save the generated PDF") String fileName,
            @ToolParam(description = "Content to be included in the PDF") String content) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
        String filePath = fileDir + "/" + fileName;
        try {
            String fontPath = System.getenv("APP_PDF_FONT_PATH");
            if (fontPath == null || fontPath.isBlank()) {
                String macOsFontPath = "/Library/Fonts/Arial Unicode.ttf";
                fontPath = new File(macOsFontPath).isFile() ? macOsFontPath : null;
            }
            if (fontPath == null || fontPath.isBlank() || !new File(fontPath).isFile()) {
                return "Error generating PDF: no usable Chinese font. Set APP_PDF_FONT_PATH to a .ttf, .otf, or .ttc font file.";
            }
            PdfFont font = PdfFontFactory.createFont(fontPath, PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
            FileUtil.mkdir(fileDir);
            try (PdfWriter writer = new PdfWriter(filePath);
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {
                document.setFont(font);
                Paragraph paragraph = new Paragraph(content);
                document.add(paragraph);
            }
            return "PDF generated successfully to: " + filePath;
        } catch (IOException e) {
            FileUtil.del(filePath);
            return "Error generating PDF: " + e.getMessage();
        }
    }
}
