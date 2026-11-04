package com.zwx.zwxartifactmcpserver.render;

import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.TextAlignment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** Markdown-lite → PDF（标题 + 段落/列表），中文需要可用的 CJK 字体。 */
@Service
public class PdfRenderer {

    private final String configuredFontPath;

    public PdfRenderer(@Value("${artifact.pdf-font-path:}") String configuredFontPath) {
        this.configuredFontPath = configuredFontPath;
    }

    public byte[] render(String title, String markdown) throws IOException {
        String fontPath = resolveFontPath();
        if (fontPath == null) {
            throw new IllegalStateException(
                    "no usable CJK font: set ARTIFACT_PDF_FONT_PATH to a .ttf/.otf/.ttc file");
        }
        PdfFont font = PdfFontFactory.createFont(fontPath, PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             PdfWriter writer = new PdfWriter(out);
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf)) {
            document.setFont(font);
            if (title != null && !title.isBlank()) {
                Paragraph heading = new Paragraph(title.trim()).setFontSize(20)
                        .setTextAlignment(TextAlignment.CENTER).setMarginBottom(16);
                document.add(heading);
            }
            List<String> lines = markdown == null ? List.of() : List.of(markdown.split("\n", -1));
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    document.add(new Paragraph(" ").setMarginBottom(4));
                    continue;
                }
                if (line.startsWith("#")) {
                    int level = 0;
                    while (level < line.length() && line.charAt(level) == '#') level += 1;
                    float size = level <= 1 ? 16 : level == 2 ? 14 : 12;
                    document.add(new Paragraph(line.substring(Math.min(level, line.length())).trim())
                            .setFontSize(size).setMarginTop(10).setMarginBottom(6));
                } else if (line.startsWith("- ") || line.startsWith("* ")) {
                    document.add(new Paragraph("• " + line.substring(2).trim()).setFontSize(11)
                            .setMarginLeft(14).setMarginBottom(2));
                } else if (line.matches("^\\d+[.)]\\s+.*")) {
                    document.add(new Paragraph(line).setFontSize(11).setMarginLeft(14).setMarginBottom(2));
                } else {
                    document.add(new Paragraph(line.replace("**", "")).setFontSize(11).setMarginBottom(6));
                }
            }
            return out.toByteArray();
        }
    }

    public String resolveFontPath() {
        if (configuredFontPath != null && !configuredFontPath.isBlank() && new File(configuredFontPath).isFile()) {
            return configuredFontPath;
        }
        String[] candidates = {
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
                "/Library/Fonts/Arial Unicode.ttf"
        };
        for (String candidate : candidates) {
            if (candidate != null && new File(candidate).isFile()) return candidate;
        }
        return null;
    }
}
