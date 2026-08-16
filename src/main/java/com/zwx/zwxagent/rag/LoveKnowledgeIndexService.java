package com.zwx.zwxagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Indexes knowledge outside application startup so large corpora never block serving traffic. */
@Service
public class LoveKnowledgeIndexService {

    private static final int BATCH_SIZE = 100;
    private final JdbcTemplate jdbcTemplate;
    private final LoveAppDocumentLoader documentLoader;
    private final MyTokenTextSplitter textSplitter;
    private final VectorStore vectorStore;

    public LoveKnowledgeIndexService(JdbcTemplate jdbcTemplate, LoveAppDocumentLoader documentLoader,
                                     MyTokenTextSplitter textSplitter, @org.springframework.beans.factory.annotation.Qualifier("loveAppVectorStore") VectorStore vectorStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentLoader = documentLoader;
        this.textSplitter = textSplitter;
        this.vectorStore = vectorStore;
    }

    public LoveKnowledgeIndexJob createBundledDocumentIndexJob() {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO love_knowledge_index_job (id, status) VALUES (?, 'PENDING')", id);
        return getJob(id);
    }

    @Async("loveKnowledgeIndexExecutor")
    public void indexBundledDocuments(String jobId) {
        try {
            jdbcTemplate.update("UPDATE love_knowledge_index_job SET status = 'INDEXING' WHERE id = ?", jobId);
            List<Document> documents = documentLoader.loadMarkdowns();
            List<Document> chunks = toChunks(documents);
            vectorStore.delete(chunks.stream().map(Document::getId).toList());
            for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
                vectorStore.add(chunks.subList(start, Math.min(start + BATCH_SIZE, chunks.size())));
            }
            jdbcTemplate.update("""
                    UPDATE love_knowledge_index_job
                    SET status = 'READY', document_count = ?, chunk_count = ?, completed_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, documents.size(), chunks.size(), jobId);
        } catch (Exception exception) {
            jdbcTemplate.update("""
                    UPDATE love_knowledge_index_job
                    SET status = 'FAILED', error_message = ?, completed_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, truncate(exception.getMessage()), jobId);
        }
    }

    public LoveKnowledgeIndexJob getJob(String jobId) {
        return jdbcTemplate.queryForObject("""
                        SELECT id, status, document_count, chunk_count, error_message, created_at, completed_at
                        FROM love_knowledge_index_job WHERE id = ?
                        """, (rs, rowNum) -> new LoveKnowledgeIndexJob(
                        rs.getString("id"), rs.getString("status"), rs.getInt("document_count"),
                        rs.getInt("chunk_count"), rs.getString("error_message"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()), jobId);
    }

    private List<Document> toChunks(List<Document> documents) {
        Map<String, Integer> chunkIndexes = new HashMap<>();
        return textSplitter.splitCustomized(documents).stream().map(document -> {
            Map<String, Object> metadata = new HashMap<>(document.getMetadata());
            String objectKey = String.valueOf(metadata.get("objectKey"));
            metadata.put("knowledgeBase", "love");
            metadata.put("chunkIndex", chunkIndexes.merge(objectKey, 1, Integer::sum));
            return Document.builder()
                    .id(UUID.nameUUIDFromBytes((objectKey + "|" + document.getText()).getBytes(StandardCharsets.UTF_8)).toString())
                    .text(document.getText()).metadata(metadata).build();
        }).toList();
    }

    private String truncate(String value) {
        if (value == null) return "索引任务失败";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
