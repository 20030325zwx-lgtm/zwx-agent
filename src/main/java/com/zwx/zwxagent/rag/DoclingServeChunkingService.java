package com.zwx.zwxagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class DoclingServeChunkingService {

    private final DoclingServeClient client;
    private final DoclingDocumentPreprocessor preprocessor;
    private final boolean fallbackToLocal;
    private final boolean furnitureChunkEnabled;

    public DoclingServeChunkingService(DoclingServeClient client,
                                       DoclingDocumentPreprocessor preprocessor,
                                       @Value("${app.document-parser.docling.fallback-to-local:true}") boolean fallbackToLocal,
                                       @Value("${app.document-parser.docling.furniture-chunk-enabled:true}") boolean furnitureChunkEnabled) {
        this.client = client;
        this.preprocessor = preprocessor;
        this.fallbackToLocal = fallbackToLocal;
        this.furnitureChunkEnabled = furnitureChunkEnabled;
    }

    public boolean shouldUse(String filename) {
        return client.supports(filename);
    }

    public boolean fallbackToLocal() {
        return fallbackToLocal;
    }

    public List<Document> chunk(byte[] bytes, String filename, Map<String, Object> baseMetadata) {
        DoclingChunkResponse response = client.chunk(bytes, filename);
        List<DoclingChunk> sourceChunks = response.chunks();
        if (sourceChunks == null || sourceChunks.isEmpty()) {
            throw new DoclingServeException("Docling Serve returned no chunks");
        }
        PreprocessedDocument preprocessed = preprocessor.preprocess(response, filename, sourceChunks);
        boolean resume = preprocessed.resume();
        List<Document> chunks = new ArrayList<>();
        for (DoclingChunk source : sourceChunks) {
            if (source.text() == null || source.text().isBlank()) continue;
            Map<String, Object> metadata = new HashMap<>(baseMetadata);
            metadata.put("parser", "docling-serve");
            metadata.put("chunking", "docling-hybrid");
            metadata.put("documentType", preprocessed.documentType());
            if (source.chunkIndex() != null) metadata.put("doclingChunkIndex", source.chunkIndex());
            if (source.numTokens() != null) metadata.put("tokenCount", source.numTokens());
            if (source.headings() != null && !source.headings().isEmpty()) {
                metadata.put("sectionPath", String.join(" > ", source.headings()));
                metadata.put("headings", source.headings());
            }
            if (source.pageNumbers() != null && !source.pageNumbers().isEmpty()) {
                metadata.put("pageNumber", source.pageNumbers().get(0));
                metadata.put("pageNumbers", new LinkedHashSet<>(source.pageNumbers()));
            }
            if (source.captions() != null && !source.captions().isEmpty()) metadata.put("captions", source.captions());
            if (source.docItems() != null && !source.docItems().isEmpty()) metadata.put("docItems", source.docItems());
            if (source.metadata() != null) metadata.put("doclingMetadata", source.metadata());
            if (resume) metadata.put("resumeSection", resumeSection(metadata, source.text()));
            chunks.add(Document.builder().text(source.text()).metadata(metadata).build());
        }
        if (chunks.isEmpty()) throw new DoclingServeException("Docling Serve returned only empty chunks");
        if (furnitureChunkEnabled && !preprocessed.furnitureText().isEmpty()) {
            String section = resume ? "基本信息" : "文档头部信息";
            String text = "[" + section + "]\n" + String.join("\n", preprocessed.furnitureText());
            Map<String, Object> metadata = new HashMap<>(baseMetadata);
            metadata.put("parser", "docling-serve");
            metadata.put("chunking", "docling-furniture");
            metadata.put("documentType", preprocessed.documentType());
            metadata.put("sectionPath", section);
            metadata.put("contentLayer", "furniture");
            if (resume) metadata.put("resumeSection", "基本信息");
            chunks.add(0, Document.builder().text(text).metadata(metadata).build());
        }
        return chunks;
    }

    private String resumeSection(Map<String, Object> metadata, String text) {
        String value = (String.valueOf(metadata.getOrDefault("sectionPath", "")) + " "
                + (text == null ? "" : text)).toLowerCase(java.util.Locale.ROOT);
        if (contains(value, "基本信息", "个人信息", "联系方式", "求职意向", "电话", "邮箱", "姓名")) return "基本信息";
        if (contains(value, "工作经历", "工作经验", "职业经历", "任职", "公司")) return "工作经历";
        if (contains(value, "项目经历", "项目经验", "项目")) return "项目经历";
        if (contains(value, "教育经历", "教育", "学校", "学历")) return "教育经历";
        if (contains(value, "专业技能", "技能", "技术栈", "专业能力")) return "专业技能";
        return "其他";
    }

    private boolean contains(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) return true;
        }
        return false;
    }
}
