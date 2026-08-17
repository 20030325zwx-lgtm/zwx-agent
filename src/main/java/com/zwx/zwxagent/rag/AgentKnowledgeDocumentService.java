package com.zwx.zwxagent.rag;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentKnowledgeDocumentService {

    private static final int BATCH_SIZE = 100;
    private final OSS ossClient;
    private final String bucket;
    private final JdbcTemplate jdbcTemplate;
    private final VectorStore vectorStore;
    private final MyTokenTextSplitter textSplitter;

    public AgentKnowledgeDocumentService(@Value("${app.oss.endpoint}") String endpoint,
                                         @Value("${app.oss.access-key-id}") String accessKeyId,
                                         @Value("${app.oss.access-key-secret}") String accessKeySecret,
                                         @Value("${app.oss.bucket}") String bucket, JdbcTemplate jdbcTemplate,
                                         @Qualifier("agentKnowledgeVectorStore") VectorStore vectorStore,
                                         MyTokenTextSplitter textSplitter) {
        if (accessKeyId.isBlank() || accessKeySecret.isBlank()) throw new IllegalStateException("OSS credentials are required");
        this.ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        this.bucket = bucket;
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
    }

    public AgentKnowledgeDocument upload(String tenantId, String agentKey, MultipartFile file) {
        validateScope(tenantId, agentKey);
        String filename = file.getOriginalFilename() == null ? "document.txt" : file.getOriginalFilename();
        if (!filename.toLowerCase().matches(".*\\.(md|txt|pdf)$")) throw new IllegalArgumentException("Only .md, .txt and .pdf knowledge files are supported");
        String id = UUID.randomUUID().toString();
        String objectKey = "knowledge/" + tenantId + "/" + agentKey + "/" + id + "-" + filename;
        try (InputStream input = file.getInputStream()) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(file.getSize());
            metadata.setContentType(filename.toLowerCase().endsWith(".pdf") ? "application/pdf" : "text/plain; charset=UTF-8");
            ossClient.putObject(bucket, objectKey, input, metadata);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to store knowledge document", exception);
        }
        jdbcTemplate.update("""
                INSERT INTO agent_knowledge_document (id, tenant_id, agent_key, object_key, filename, status)
                VALUES (?, ?, ?, ?, ?, 'PENDING')
                """, id, tenantId, agentKey, objectKey, filename);
        return getDocumentRecord(tenantId, agentKey, id);
    }

    @Async("loveKnowledgeIndexExecutor")
    public void indexDocument(String documentId) {
        try {
            jdbcTemplate.update("UPDATE agent_knowledge_document SET status = 'INDEXING' WHERE id = ?", documentId);
            StoredDocument stored = jdbcTemplate.queryForObject("SELECT tenant_id, agent_key, object_key, filename FROM agent_knowledge_document WHERE id = ?",
                    (rs, rowNum) -> new StoredDocument(rs.getString("tenant_id"), rs.getString("agent_key"), rs.getString("object_key"), rs.getString("filename")), documentId);
            String content;
            try (var object = ossClient.getObject(bucket, stored.objectKey()); var input = object.getObjectContent()) {
                content = extractContent(input.readAllBytes(), stored.filename());
            }
            if (content.isBlank()) throw new IllegalArgumentException("PDF does not contain extractable text; OCR is required for scanned documents");
            List<Document> splitChunks = textSplitter.splitCustomized(List.of(Document.builder().id(documentId).text(content)
                    .metadata(Map.of("tenantId", stored.tenantId(), "agentKey", stored.agentKey(), "objectKey", stored.objectKey(), "filename", stored.filename())).build()));
            List<Document> chunks = java.util.stream.IntStream.range(0, splitChunks.size())
                    .mapToObj(index -> toScopedChunk(splitChunks.get(index), documentId, stored, index + 1)).toList();
            vectorStore.delete(chunks.stream().map(Document::getId).toList());
            for (int start = 0; start < chunks.size(); start += BATCH_SIZE) vectorStore.add(chunks.subList(start, Math.min(start + BATCH_SIZE, chunks.size())));
            jdbcTemplate.update("UPDATE agent_knowledge_document SET status = 'READY', chunk_count = ?, completed_at = CURRENT_TIMESTAMP WHERE id = ?", chunks.size(), documentId);
        } catch (Exception exception) {
            jdbcTemplate.update("UPDATE agent_knowledge_document SET status = 'FAILED', error_message = ?, completed_at = CURRENT_TIMESTAMP WHERE id = ?",
                    truncate(exception.getMessage()), documentId);
        }
    }

    public List<AgentKnowledgeDocument> listDocuments(String tenantId, String agentKey) {
        validateScope(tenantId, agentKey);
        return jdbcTemplate.query("""
                SELECT id, tenant_id, agent_key, filename, status, chunk_count, error_message, created_at, completed_at
                FROM agent_knowledge_document WHERE tenant_id = ? AND agent_key = ? ORDER BY created_at DESC
                """, (rs, rowNum) -> mapDocument(rs), tenantId, agentKey);
    }

    public AgentKnowledgeDocumentDetail getDocument(String tenantId, String agentKey, String documentId) {
        validateScope(tenantId, agentKey);
        StoredDocument stored = jdbcTemplate.queryForObject("""
                SELECT tenant_id, agent_key, object_key, filename FROM agent_knowledge_document
                WHERE id = ? AND tenant_id = ? AND agent_key = ?
                """, (rs, rowNum) -> new StoredDocument(rs.getString("tenant_id"), rs.getString("agent_key"),
                rs.getString("object_key"), rs.getString("filename")), documentId, tenantId, agentKey);
        String content;
        try (var object = ossClient.getObject(bucket, stored.objectKey()); var input = object.getObjectContent()) {
            content = extractContent(input.readAllBytes(), stored.filename());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read knowledge document", exception);
        }
        List<AgentKnowledgeChunk> chunks = jdbcTemplate.query("""
                SELECT id::text, content, NULLIF(metadata ->> 'chunkIndex', '')::integer AS chunk_index
                FROM agent_knowledge_vector
                WHERE metadata ->> 'documentId' = ? AND metadata ->> 'tenantId' = ? AND metadata ->> 'agentKey' = ?
                ORDER BY chunk_index NULLS LAST, id
                """, (rs, rowNum) -> new AgentKnowledgeChunk(rs.getString("id"),
                rs.getObject("chunk_index", Integer.class) == null ? rowNum + 1 : rs.getInt("chunk_index"), rs.getString("content")),
                documentId, tenantId, agentKey);
        return new AgentKnowledgeDocumentDetail(documentId, stored.filename(), stored.objectKey(), content, chunks);
    }

    private AgentKnowledgeDocument getDocumentRecord(String tenantId, String agentKey, String id) {
        return jdbcTemplate.queryForObject("""
                SELECT id, tenant_id, agent_key, filename, status, chunk_count, error_message, created_at, completed_at
                FROM agent_knowledge_document WHERE id = ? AND tenant_id = ? AND agent_key = ?
                """, (rs, rowNum) -> mapDocument(rs), id, tenantId, agentKey);
    }

    private AgentKnowledgeDocument mapDocument(java.sql.ResultSet rs) throws java.sql.SQLException {
        var completedAt = rs.getTimestamp("completed_at");
        return new AgentKnowledgeDocument(rs.getString("id"), rs.getString("tenant_id"), rs.getString("agent_key"), rs.getString("filename"),
                rs.getString("status"), rs.getInt("chunk_count"), rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant());
    }

    private Document toScopedChunk(Document document, String documentId, StoredDocument stored, int chunkIndex) {
        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        metadata.put("documentId", documentId);
        metadata.put("tenantId", stored.tenantId());
        metadata.put("agentKey", stored.agentKey());
        metadata.put("chunkIndex", chunkIndex);
        return Document.builder().id(UUID.nameUUIDFromBytes((documentId + "|" + document.getText()).getBytes(StandardCharsets.UTF_8)).toString())
                .text(document.getText()).metadata(metadata).build();
    }

    private String extractContent(byte[] bytes, String filename) {
        if (!filename.toLowerCase().endsWith(".pdf")) return new String(bytes, StandardCharsets.UTF_8);
        StringBuilder text = new StringBuilder();
        try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(bytes)))) {
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                text.append("\n[PDF 第 ").append(page).append(" 页]\n").append(PdfTextExtractor.getTextFromPage(pdf.getPage(page)));
            }
        } catch (Exception exception) { throw new IllegalArgumentException("Unable to extract PDF text", exception); }
        return text.toString();
    }

    private void validateScope(String tenantId, String agentKey) {
        if (!tenantId.matches("[A-Za-z0-9_-]{1,64}") || !(agentKey.equals("love") || agentKey.equals("travel"))) throw new IllegalArgumentException("Invalid tenant or agent scope");
    }

    private String truncate(String value) { return value == null ? "Indexing failed" : value.substring(0, Math.min(1000, value.length())); }
    private record StoredDocument(String tenantId, String agentKey, String objectKey, String filename) { }
}
