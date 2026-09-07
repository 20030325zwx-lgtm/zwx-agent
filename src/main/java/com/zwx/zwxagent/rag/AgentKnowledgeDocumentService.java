package com.zwx.zwxagent.rag;

import com.aliyun.oss.model.ObjectMetadata;
import com.zwx.zwxagent.storage.OssClientProvider;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentKnowledgeDocumentService {

    private static final int BATCH_SIZE = 100;
    private final OssClientProvider ossClientProvider;
    private final String bucket;
    private final JdbcTemplate jdbcTemplate;
    private final VectorStore vectorStore;
    private final MyTokenTextSplitter textSplitter;
    private final DocumentParsingModule documentParsingModule;
    private final TransactionTemplate txTemplate;

    public AgentKnowledgeDocumentService(OssClientProvider ossClientProvider,
                                         @Value("${app.oss.bucket}") String bucket, JdbcTemplate jdbcTemplate,
                                         @Qualifier("agentKnowledgeVectorStore") VectorStore vectorStore,
                                         MyTokenTextSplitter textSplitter, DocumentParsingModule documentParsingModule,
                                         PlatformTransactionManager transactionManager) {
        this.ossClientProvider = ossClientProvider;
        this.bucket = bucket;
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
        this.documentParsingModule = documentParsingModule;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 上传创建新版本：同一逻辑文档（logical_key）版本号递增，旧版本保留可回溯；
     * 不再删除同名旧版本。发布在索引成功后自动执行（兼容旧行为，publish 支持后续人工发布）。
     */
    public AgentKnowledgeDocument upload(String tenantId, String agentKey, MultipartFile file, String createdBy) {
        validateScope(tenantId, agentKey);
        String filename = file.getOriginalFilename() == null ? "document.txt" : file.getOriginalFilename();
        if (!filename.toLowerCase().matches(".*\\.(md|txt|pdf|doc|docx|xls|xlsx|ppt|pptx)$"))
            throw new IllegalArgumentException("Supported knowledge files: md, txt, pdf, doc, docx, xls, xlsx, ppt, pptx");
        String logicalKey = LogicalKeys.normalize(filename);
        byte[] content;
        try {
            content = file.getBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read knowledge document", exception);
        }
        String id = UUID.randomUUID().toString();
        String objectKey = "knowledge/" + tenantId + "/" + agentKey + "/" + id + "-" + filename;
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(content.length);
            metadata.setContentType(filename.toLowerCase().endsWith(".pdf") ? "application/pdf" : "text/plain; charset=UTF-8");
            ossClientProvider.getClient().putObject(bucket, objectKey, new ByteArrayInputStream(content), metadata);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to store knowledge document", exception);
        }
        String supersedesId = jdbcTemplate.query("""
                        SELECT id FROM agent_knowledge_document
                        WHERE tenant_id = ? AND agent_key = ? AND logical_key = ? AND lifecycle_status = 'ACTIVE'
                        ORDER BY version_no DESC LIMIT 1
                        """, (rs, rowNum) -> rs.getString("id"), tenantId, agentKey, logicalKey)
                .stream().findFirst().orElse(null);
        Integer nextVersion = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version_no), 0) + 1 FROM agent_knowledge_document
                WHERE tenant_id = ? AND agent_key = ? AND logical_key = ?
                """, Integer.class, tenantId, agentKey, logicalKey);
        jdbcTemplate.update("""
                INSERT INTO agent_knowledge_document
                    (id, tenant_id, agent_key, object_key, filename, status,
                     logical_key, version_no, lifecycle_status, content_sha256, supersedes_document_id, created_by)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, 'INDEXING', ?, ?, ?)
                """, id, tenantId, agentKey, objectKey, filename, logicalKey, nextVersion,
                sha256(content), supersedesId, createdBy);
        return getDocumentRecord(tenantId, agentKey, id);
    }

    public void deleteDocument(String tenantId, String agentKey, String documentId) {
        validateScope(tenantId, agentKey);
        StoredDocument stored;
        try {
            stored = queryStoredDocument(documentId, true);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw new IllegalArgumentException("Document does not exist in the current scope");
        }
        removeDocumentCompletely(documentId);
        try {
            ossClientProvider.getClient().deleteObject(bucket, stored.objectKey());
        } catch (Exception ignored) {
        }
    }

    private void removeDocumentCompletely(String documentId) {
        List<String> objectKeys = jdbcTemplate.queryForList(
                "SELECT object_key FROM agent_knowledge_document WHERE id = ?", String.class, documentId);
        List<String> chunkIds = jdbcTemplate.queryForList("""
                SELECT id::text FROM agent_knowledge_vector
                WHERE metadata ->> 'documentId' = ?
                """, String.class, documentId);
        if (!chunkIds.isEmpty()) vectorStore.delete(chunkIds);
        jdbcTemplate.update("DELETE FROM agent_knowledge_document WHERE id = ?", documentId);
        for (String objectKey : objectKeys) {
            try {
                ossClientProvider.getClient().deleteObject(bucket, objectKey);
            } catch (Exception ignored) {
            }
        }
    }

    @Async("loveKnowledgeIndexExecutor")
    public void indexDocument(String documentId) {
        String originalLifecycle;
        try {
            originalLifecycle = jdbcTemplate.queryForObject(
                    "SELECT lifecycle_status FROM agent_knowledge_document WHERE id = ?", String.class, documentId);
        } catch (Exception exception) {
            return;
        }
        if (originalLifecycle == null) return;
        try {
            jdbcTemplate.update("UPDATE agent_knowledge_document SET status = 'INDEXING' WHERE id = ?", documentId);
            StoredDocument stored = queryStoredDocument(documentId, false);
            originalLifecycle = stored.lifecycleStatus();
            ParsedDocument parsed;
            try (var object = ossClientProvider.getClient().getObject(bucket, stored.objectKey()); var input = object.getObjectContent()) {
                parsed = documentParsingModule.parse(input.readAllBytes(), stored.filename());
            }
            String content = parsed.content();
            if (content.isBlank()) throw new IllegalArgumentException("PDF does not contain extractable text; OCR is required for scanned documents");
            Map<String, Object> sourceMetadata = new HashMap<>();
            sourceMetadata.put("tenantId", stored.tenantId());
            sourceMetadata.put("agentKey", stored.agentKey());
            sourceMetadata.put("objectKey", stored.objectKey());
            sourceMetadata.put("filename", stored.filename());
            sourceMetadata.put("parser", parsed.parser());
            sourceMetadata.putAll(parsed.metadata());
            List<Document> splitChunks = textSplitter.splitStructuredMarkdown(List.of(Document.builder().id(documentId).text(content)
                    .metadata(sourceMetadata).build()));
            List<Document> chunks = java.util.stream.IntStream.range(0, splitChunks.size())
                    .mapToObj(index -> toScopedChunk(splitChunks.get(index), stored, index + 1)).toList();
            List<String> existingChunkIds = jdbcTemplate.queryForList("""
                    SELECT id::text FROM agent_knowledge_vector
                    WHERE metadata ->> 'documentId' = ? AND metadata ->> 'tenantId' = ? AND metadata ->> 'agentKey' = ?
                    """, String.class, documentId, stored.tenantId(), stored.agentKey());
            if (!existingChunkIds.isEmpty()) vectorStore.delete(existingChunkIds);
            for (int start = 0; start < chunks.size(); start += BATCH_SIZE) vectorStore.add(chunks.subList(start, Math.min(start + BATCH_SIZE, chunks.size())));
            jdbcTemplate.update("""
                    UPDATE agent_knowledge_document SET status = 'READY', lifecycle_status = 'READY',
                        chunk_count = ?, completed_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, chunks.size(), documentId);
        } catch (Exception exception) {
            // 新版本索引失败标记 FAILED；已发布版本（ACTIVE/ARCHIVED）重建索引失败保持原状态，避免误下线
            jdbcTemplate.update("""
                    UPDATE agent_knowledge_document SET status = 'FAILED',
                        lifecycle_status = CASE WHEN ? = 'INDEXING' THEN 'FAILED' ELSE ? END,
                        error_message = ?, completed_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, originalLifecycle, originalLifecycle, truncate(exception.getMessage()), documentId);
            return;
        }
        // 新版本流程（INDEXING → READY）自动发布，兼容旧上传即生效行为；人工发布接口后续开放
        if ("INDEXING".equals(originalLifecycle)) {
            try {
                publish(documentId);
            } catch (Exception exception) {
                org.slf4j.LoggerFactory.getLogger(AgentKnowledgeDocumentService.class)
                        .warn("Auto publish failed for document {}, stays READY: {}", documentId, exception.getMessage());
            }
        }
    }

    /**
     * 原子发布：旧 ACTIVE → ARCHIVED，目标 READY → ACTIVE，并同步翻转向量切片元数据。
     * 全程在同一事务内，行锁串行化同一逻辑文档的发布；发布失败整体回滚，旧版本保持生效。
     */
    public void publish(String documentId) {
        StoredDocument stored = queryStoredDocument(documentId, false);
        txTemplate.executeWithoutResult(transactionStatus -> {
            List<String> chainIds = jdbcTemplate.queryForList("""
                    SELECT id FROM agent_knowledge_document
                    WHERE tenant_id = ? AND agent_key = ? AND logical_key = ?
                    FOR UPDATE
                    """, String.class, stored.tenantId(), stored.agentKey(), stored.logicalKey());
            String currentLifecycle = jdbcTemplate.queryForObject(
                    "SELECT lifecycle_status FROM agent_knowledge_document WHERE id = ?", String.class, documentId);
            if (!"READY".equals(currentLifecycle) && !"ACTIVE".equals(currentLifecycle))
                throw new IllegalStateException("Only READY versions can be published, current: " + currentLifecycle);
            String activeId = chainIds.stream()
                    .filter(id -> !id.equals(documentId))
                    .filter(id -> "ACTIVE".equals(jdbcTemplate.queryForObject(
                            "SELECT lifecycle_status FROM agent_knowledge_document WHERE id = ?", String.class, id)))
                    .findFirst().orElse(null);
            if (activeId != null) {
                jdbcTemplate.update("""
                        UPDATE agent_knowledge_document SET lifecycle_status = 'ARCHIVED',
                            archived_at = CURRENT_TIMESTAMP, effective_to = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, activeId);
                flipChunkLifecycle(activeId, "ARCHIVED");
            }
            jdbcTemplate.update("""
                    UPDATE agent_knowledge_document SET lifecycle_status = 'ACTIVE',
                        published_at = CURRENT_TIMESTAMP, effective_from = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, documentId);
            flipChunkLifecycle(documentId, "ACTIVE");
        });
    }

    private void flipChunkLifecycle(String documentId, String lifecycleStatus) {
        jdbcTemplate.update("""
                UPDATE agent_knowledge_vector
                SET metadata = (metadata::jsonb || jsonb_build_object('lifecycleStatus', ?))::json
                WHERE metadata ->> 'documentId' = ?
                  AND metadata ->> 'lifecycleStatus' IS DISTINCT FROM ?
                """, lifecycleStatus, documentId, lifecycleStatus);
    }

    public List<AgentKnowledgeDocument> listDocuments(String tenantId, String agentKey) {
        validateScope(tenantId, agentKey);
        return jdbcTemplate.query("""
                SELECT id, tenant_id, agent_key, filename, status, chunk_count, error_message, created_at, completed_at,
                       logical_key, version_no, lifecycle_status, content_sha256, published_at, archived_at, created_by
                FROM agent_knowledge_document WHERE tenant_id = ? AND agent_key = ?
                ORDER BY logical_key, version_no DESC
                """, (rs, rowNum) -> mapDocument(rs), tenantId, agentKey);
    }

    public AgentKnowledgeDocumentDetail getDocument(String tenantId, String agentKey, String documentId) {
        validateScope(tenantId, agentKey);
        StoredDocument stored = queryStoredDocument(documentId, true);
        try (var object = ossClientProvider.getClient().getObject(bucket, stored.objectKey()); var input = object.getObjectContent()) {
            ParsedDocument parsed = documentParsingModule.parse(input.readAllBytes(), stored.filename());
            List<AgentKnowledgeChunk> chunks = jdbcTemplate.query("""
                    SELECT id::text, content, NULLIF(metadata ->> 'chunkIndex', '')::integer AS chunk_index
                    FROM agent_knowledge_vector
                    WHERE metadata ->> 'documentId' = ? AND metadata ->> 'tenantId' = ? AND metadata ->> 'agentKey' = ?
                    ORDER BY chunk_index NULLS LAST, id
                    """, (rs, rowNum) -> new AgentKnowledgeChunk(rs.getString("id"),
                    rs.getObject("chunk_index", Integer.class) == null ? rowNum + 1 : rs.getInt("chunk_index"), rs.getString("content")),
                    documentId, tenantId, agentKey);
            return new AgentKnowledgeDocumentDetail(documentId, stored.filename(), stored.objectKey(), parsed.content(), chunks);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read knowledge document", exception);
        }
    }

    public AgentKnowledgeDocument getDocumentRecordForScope(String tenantId, String agentKey, String documentId) {
        validateScope(tenantId, agentKey);
        return getDocumentRecord(tenantId, agentKey, documentId);
    }

    private AgentKnowledgeDocument getDocumentRecord(String tenantId, String agentKey, String id) {
        return jdbcTemplate.queryForObject("""
                SELECT id, tenant_id, agent_key, filename, status, chunk_count, error_message, created_at, completed_at,
                       logical_key, version_no, lifecycle_status, content_sha256, published_at, archived_at, created_by
                FROM agent_knowledge_document WHERE id = ? AND tenant_id = ? AND agent_key = ?
                """, (rs, rowNum) -> mapDocument(rs), id, tenantId, agentKey);
    }

    private AgentKnowledgeDocument mapDocument(java.sql.ResultSet rs) throws java.sql.SQLException {
        var completedAt = rs.getTimestamp("completed_at");
        var publishedAt = rs.getTimestamp("published_at");
        var archivedAt = rs.getTimestamp("archived_at");
        return new AgentKnowledgeDocument(rs.getString("id"), rs.getString("tenant_id"), rs.getString("agent_key"), rs.getString("filename"),
                rs.getString("status"), rs.getInt("chunk_count"), rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant(),
                rs.getString("logical_key"), (Integer) rs.getObject("version_no"), rs.getString("lifecycle_status"),
                rs.getString("content_sha256"),
                publishedAt == null ? null : publishedAt.toInstant(),
                archivedAt == null ? null : archivedAt.toInstant(),
                rs.getString("created_by"));
    }

    private Document toScopedChunk(Document document, StoredDocument stored, int chunkIndex) {
        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        metadata.put("documentId", stored.documentId());
        metadata.put("tenantId", stored.tenantId());
        metadata.put("agentKey", stored.agentKey());
        metadata.put("logicalKey", stored.logicalKey());
        metadata.put("versionNo", stored.versionNo());
        metadata.put("lifecycleStatus", stored.lifecycleStatus());
        metadata.put("chunkIndex", chunkIndex);
        return Document.builder().id(UUID.nameUUIDFromBytes((stored.documentId() + "|" + document.getText()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString())
                .text(document.getText()).metadata(metadata).build();
    }

    private StoredDocument queryStoredDocument(String documentId, boolean strictScope) {
        return jdbcTemplate.queryForObject("""
                SELECT id, tenant_id, agent_key, object_key, filename, logical_key, version_no, lifecycle_status
                FROM agent_knowledge_document WHERE id = ?
                """, (rs, rowNum) -> {
            StoredDocument stored = new StoredDocument(rs.getString("id"), rs.getString("tenant_id"), rs.getString("agent_key"),
                    rs.getString("object_key"), rs.getString("filename"), rs.getString("logical_key"),
                    (Integer) rs.getObject("version_no"), rs.getString("lifecycle_status"));
            if (strictScope) return stored;
            validateScope(stored.tenantId(), stored.agentKey());
            return stored;
        }, documentId);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            return null;
        }
    }

    private void validateScope(String tenantId, String agentKey) {
        if (!tenantId.matches("[A-Za-z0-9_-]{1,64}") || !(agentKey.equals("love") || agentKey.equals("travel") || agentKey.equals("test") || agentKey.equals("super"))) throw new IllegalArgumentException("Invalid tenant or agent scope");
    }

    private String truncate(String value) { return value == null ? "Indexing failed" : value.substring(0, Math.min(1000, value.length())); }
    private record StoredDocument(String documentId, String tenantId, String agentKey, String objectKey, String filename,
                                  String logicalKey, Integer versionNo, String lifecycleStatus) { }
}
