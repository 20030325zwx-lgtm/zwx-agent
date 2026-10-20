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
    private final boolean fallbackToLocal;

    public DoclingServeChunkingService(DoclingServeClient client,
                                       @Value("${app.document-parser.docling.fallback-to-local:true}") boolean fallbackToLocal) {
        this.client = client;
        this.fallbackToLocal = fallbackToLocal;
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
        List<Document> chunks = new ArrayList<>();
        for (DoclingChunk source : sourceChunks) {
            if (source.text() == null || source.text().isBlank()) continue;
            Map<String, Object> metadata = new HashMap<>(baseMetadata);
            metadata.put("parser", "docling-serve");
            metadata.put("chunking", "docling-hybrid");
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
            chunks.add(Document.builder().text(source.text()).metadata(metadata).build());
        }
        if (chunks.isEmpty()) throw new DoclingServeException("Docling Serve returned only empty chunks");
        return chunks;
    }
}
