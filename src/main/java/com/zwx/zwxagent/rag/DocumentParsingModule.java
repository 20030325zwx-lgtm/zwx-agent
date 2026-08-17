package com.zwx.zwxagent.rag;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single entry point for document parsing. It intentionally stops before chunking and embedding.
 * Parser priority: MinerU for PDF, optional Tika Server for general files, then safe local fallback.
 */
@Service
public class DocumentParsingModule {
    private final MineruPdfExtractor mineruPdfExtractor;
    private final TikaRemoteParser tikaRemoteParser;

    public DocumentParsingModule(MineruPdfExtractor mineruPdfExtractor, TikaRemoteParser tikaRemoteParser) {
        this.mineruPdfExtractor = mineruPdfExtractor;
        this.tikaRemoteParser = tikaRemoteParser;
    }

    public ParsedDocument parse(byte[] bytes, String filename) {
        String normalizedFilename = filename == null || filename.isBlank() ? "document.txt" : filename;
        String contentType = contentType(normalizedFilename);
        if (isPdf(normalizedFilename)) {
            Optional<String> mineru = mineruPdfExtractor.extract(bytes);
            if (mineru.isPresent()) return new ParsedDocument(mineru.get(), "mineru", List.of(), Map.of("filename", normalizedFilename));
        }
        Optional<ParsedDocument> tika = tikaRemoteParser.parse(bytes, normalizedFilename, contentType);
        if (tika.isPresent()) return tika.get();
        if (isPdf(normalizedFilename)) return parsePdfWithIText(bytes, normalizedFilename);
        if (!isText(normalizedFilename)) {
            throw new IllegalArgumentException("Tika Server is required to parse " + normalizedFilename);
        }
        return new ParsedDocument(new String(bytes, StandardCharsets.UTF_8), "plain-text", List.of(), Map.of("filename", normalizedFilename));
    }

    private ParsedDocument parsePdfWithIText(byte[] bytes, String filename) {
        StringBuilder text = new StringBuilder();
        try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(bytes)))) {
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                text.append("\n[PDF 第 ").append(page).append(" 页]\n")
                        .append(PdfTextExtractor.getTextFromPage(pdf.getPage(page)));
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to extract PDF text", exception);
        }
        return new ParsedDocument(text.toString(), "itext", List.of(), Map.of("filename", filename));
    }

    private boolean isPdf(String filename) { return filename.toLowerCase().endsWith(".pdf"); }

    private boolean isText(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".txt");
    }

    private String contentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".md")) return "text/markdown";
        return "text/plain";
    }
}
