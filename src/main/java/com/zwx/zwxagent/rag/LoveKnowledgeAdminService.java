package com.zwx.zwxagent.rag;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only inspection API for the actual pgvector knowledge base. */
@Service
public class LoveKnowledgeAdminService {

    private static final String VECTOR_TABLE = "love_knowledge_vector";

    private final JdbcTemplate jdbcTemplate;
    private final ResourcePatternResolver resourcePatternResolver;

    public LoveKnowledgeAdminService(JdbcTemplate jdbcTemplate, ResourcePatternResolver resourcePatternResolver) {
        this.jdbcTemplate = jdbcTemplate;
        this.resourcePatternResolver = resourcePatternResolver;
    }

    public List<LoveKnowledgeDocumentSummary> listDocuments() {
        Map<String, SourceDocument> bundledDocuments = bundledDocuments();
        Map<String, DocumentStats> statsByObjectKey = new LinkedHashMap<>();
        jdbcTemplate.query("""
                        SELECT metadata ->> 'filename' AS filename,
                               metadata ->> 'objectKey' AS object_key,
                               COUNT(*) AS chunk_count,
                               COUNT(DISTINCT metadata ->> 'section') AS section_count
                        FROM love_knowledge_vector
                        GROUP BY metadata ->> 'filename', metadata ->> 'objectKey'
                        ORDER BY metadata ->> 'filename'
                        """,
                (RowCallbackHandler) rs -> statsByObjectKey.put(rs.getString("object_key"), new DocumentStats(
                        rs.getString("filename"), rs.getInt("chunk_count"), rs.getInt("section_count"))));

        Map<String, LoveKnowledgeDocumentSummary> documents = new LinkedHashMap<>();
        bundledDocuments.forEach((objectKey, document) -> {
            DocumentStats stats = statsByObjectKey.remove(objectKey);
            documents.put(objectKey, new LoveKnowledgeDocumentSummary(document.filename(), objectKey,
                    stats == null ? 0 : stats.chunkCount(), stats == null ? 0 : stats.sectionCount(), true, true));
        });
        statsByObjectKey.forEach((objectKey, stats) -> documents.put(objectKey,
                new LoveKnowledgeDocumentSummary(stats.filename(), objectKey, stats.chunkCount(),
                        stats.sectionCount(), false, false)));
        return new ArrayList<>(documents.values());
    }

    public LoveKnowledgeDocumentDetail getDocument(String objectKey) {
        SourceDocument bundledDocument = bundledDocuments().get(objectKey);
        List<LoveKnowledgeChunk> chunks = jdbcTemplate.query("""
                        SELECT id::text, content,
                               NULLIF(metadata ->> 'section', '')::integer AS section,
                               NULLIF(metadata ->> 'chunkIndex', '')::integer AS chunk_index
                        FROM love_knowledge_vector
                        WHERE metadata ->> 'objectKey' = ?
                        ORDER BY chunk_index NULLS LAST, section NULLS LAST, id
                        """,
                (rs, rowNum) -> new LoveKnowledgeChunk(
                        rs.getString("id"),
                        rs.getObject("chunk_index", Integer.class) == null ? rowNum + 1 : rs.getInt("chunk_index"),
                        rs.getObject("section", Integer.class),
                        rs.getString("content")), objectKey);
        if (bundledDocument == null && chunks.isEmpty()) {
            throw new IllegalArgumentException("Knowledge document was not found");
        }
        String filename = bundledDocument != null ? bundledDocument.filename() : filenameFromChunks(objectKey);
        return new LoveKnowledgeDocumentDetail(filename, objectKey,
                bundledDocument == null ? null : bundledDocument.content(), chunks);
    }

    private String filenameFromChunks(String objectKey) {
        return jdbcTemplate.queryForObject("""
                        SELECT metadata ->> 'filename'
                        FROM love_knowledge_vector
                        WHERE metadata ->> 'objectKey' = ?
                        LIMIT 1
                        """, String.class, objectKey);
    }

    private Map<String, SourceDocument> bundledDocuments() {
        try {
            Map<String, SourceDocument> documents = new LinkedHashMap<>();
            for (Resource resource : resourcePatternResolver.getResources("classpath:document/*.md")) {
                String filename = resource.getFilename();
                if (filename != null) {
                    String objectKey = "knowledge/love/" + filename;
                    try (var inputStream = resource.getInputStream()) {
                        documents.put(objectKey, new SourceDocument(filename,
                                new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)));
                    }
                }
            }
            return documents;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read bundled knowledge documents", exception);
        }
    }

    private record SourceDocument(String filename, String content) {
    }

    private record DocumentStats(String filename, int chunkCount, int sectionCount) {
    }
}
