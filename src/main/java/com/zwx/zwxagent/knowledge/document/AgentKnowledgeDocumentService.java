package com.zwx.zwxagent.knowledge.document;

import com.aliyun.oss.model.ObjectMetadata;
import com.zwx.zwxagent.knowledge.storage.OssClientProvider;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentKnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(AgentKnowledgeDocumentService.class);

    // DashScope embedding 单次最多 25 条文本，超过会直接报 "The input texts limit 25"。
    private static final int BATCH_SIZE = 25;
    private static final String DEFAULT_CATEGORY_NAME = "默认分类";
    private final OssClientProvider ossClientProvider;
    private final String bucket;
    private final JdbcTemplate jdbcTemplate;
    private final VectorStore vectorStore;
    private final MyTokenTextSplitter textSplitter;
    private final DocumentParsingModule documentParsingModule;
    private final DoclingServeChunkingService doclingChunkingService;
    private final MineruDocumentParser mineruDocumentParser;
    private final String documentParserProvider;
    private final TransactionTemplate txTemplate;

    public AgentKnowledgeDocumentService(OssClientProvider ossClientProvider,
                                         @Value("${app.oss.bucket}") String bucket, JdbcTemplate jdbcTemplate,
                                         @Qualifier("agentKnowledgeVectorStore") VectorStore vectorStore,
                                         MyTokenTextSplitter textSplitter, DocumentParsingModule documentParsingModule,
                                         DoclingServeChunkingService doclingChunkingService,
                                         MineruDocumentParser mineruDocumentParser,
                                         @Value("${app.document-parser.provider:mineru}") String documentParserProvider,
                                         PlatformTransactionManager transactionManager) {
        this.ossClientProvider = ossClientProvider;
        this.bucket = bucket;
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
        this.documentParsingModule = documentParsingModule;
        this.doclingChunkingService = doclingChunkingService;
        this.mineruDocumentParser = mineruDocumentParser;
        this.documentParserProvider = documentParserProvider == null || documentParserProvider.isBlank()
                ? "mineru" : documentParserProvider.trim().toLowerCase(java.util.Locale.ROOT);
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 上传创建新版本：同一逻辑文档（logical_key）版本号递增，旧版本保留可回溯；
     * 不再删除同名旧版本。发布在索引成功后自动执行（兼容旧行为，publish 支持后续人工发布）。
     */
    public AgentKnowledgeDocument upload(String tenantId, String agentKey, MultipartFile file, String createdBy) {
        return upload(tenantId, agentKey, file, createdBy, null);
    }

    public AgentKnowledgeDocument upload(String tenantId, String agentKey, MultipartFile file, String createdBy,
                                         String categoryId) {
        validateScope(tenantId, agentKey);
        String resolvedCategoryId = resolveCategoryId(tenantId, agentKey, categoryId);
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
                     logical_key, version_no, lifecycle_status, content_sha256, supersedes_document_id, created_by,
                     category_id)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, 'INDEXING', ?, ?, ?, ?)
                """, id, tenantId, agentKey, objectKey, filename, logicalKey, nextVersion,
                sha256(content), supersedesId, createdBy, resolvedCategoryId);
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

    @Async("knowledgeIndexExecutor")
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
            jdbcTemplate.update("UPDATE agent_knowledge_document SET status = 'INDEXING', index_attempts = index_attempts + 1, "
                    + "error_message = NULL, next_retry_at = NULL WHERE id = ?", documentId);
            StoredDocument stored = queryStoredDocument(documentId, false);
            originalLifecycle = stored.lifecycleStatus();
            byte[] bytes;
            try (var object = ossClientProvider.getClient().getObject(bucket, stored.objectKey()); var input = object.getObjectContent()) {
                bytes = input.readAllBytes();
            }
            Map<String, Object> sourceMetadata = new HashMap<>();
            sourceMetadata.put("tenantId", stored.tenantId());
            sourceMetadata.put("agentKey", stored.agentKey());
            sourceMetadata.put("objectKey", stored.objectKey());
            sourceMetadata.put("filename", stored.filename());
            List<Document> splitChunks = null;
            if ("mineru".equals(documentParserProvider) && mineruDocumentParser.isConfigured()
                    && mineruDocumentParser.supports(stored.filename())) {
                try {
                    splitChunks = mineruChunks(bytes, stored, sourceMetadata, documentId);
                } catch (Exception exception) {
                    log.warn("MinerU knowledge parsing failed, falling back; filename={}, reason={}",
                            stored.filename(), safeMessage(exception));
                }
            }
            boolean useDocling = "docling".equals(documentParserProvider)
                    || ("mineru".equals(documentParserProvider) && splitChunks == null);
            if (splitChunks == null && useDocling && doclingChunkingService.shouldUse(stored.filename())) {
                try {
                    splitChunks = doclingChunkingService.chunk(bytes, stored.filename(), sourceMetadata);
                } catch (DoclingServeException exception) {
                    if (!doclingChunkingService.fallbackToLocal()) throw exception;
                    log.warn("Docling Serve unavailable, falling back to local parser; filename={}, reason={}",
                            stored.filename(), exception.getMessage());
                }
            }
            if (splitChunks == null) {
                splitChunks = localChunks(bytes, stored, sourceMetadata, documentId);
            }
            final List<Document> resolvedChunks = splitChunks;
            List<Document> chunks = java.util.stream.IntStream.range(0, resolvedChunks.size())
                    .mapToObj(index -> toScopedChunk(resolvedChunks.get(index), stored, index + 1)).toList();
            // 扫描件可能只有页码占位而没有可索引文本；不能把 0 切片当成成功版本发布。
            if (chunks.isEmpty()) {
                throw new IllegalStateException("No extractable text after parsing; OCR may have timed out or the document is image-only");
            }
            List<String> existingChunkIds = jdbcTemplate.queryForList("""
                    SELECT id::text FROM agent_knowledge_vector
                    WHERE metadata ->> 'documentId' = ? AND metadata ->> 'tenantId' = ? AND metadata ->> 'agentKey' = ?
                    """, String.class, documentId, stored.tenantId(), stored.agentKey());
            if (!existingChunkIds.isEmpty()) vectorStore.delete(existingChunkIds);
            for (int start = 0; start < chunks.size(); start += BATCH_SIZE) vectorStore.add(chunks.subList(start, Math.min(start + BATCH_SIZE, chunks.size())));
            jdbcTemplate.update("""
                    UPDATE agent_knowledge_document SET status = 'READY',
                        lifecycle_status = CASE WHEN ? IN ('INDEXING', 'FAILED') THEN 'READY' ELSE ? END,
                        error_message = NULL, next_retry_at = NULL,
                        chunk_count = ?, completed_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, originalLifecycle, originalLifecycle, chunks.size(), documentId);
        } catch (Exception exception) {
            // 新版本索引失败标记 FAILED；已发布版本（ACTIVE/ARCHIVED）重建索引失败保持原状态，避免误下线
            jdbcTemplate.update("""
                    UPDATE agent_knowledge_document SET status = 'FAILED',
                        lifecycle_status = CASE WHEN ? = 'INDEXING' THEN 'FAILED' ELSE ? END,
                        error_message = ?, completed_at = CURRENT_TIMESTAMP,
                        next_retry_at = CASE WHEN ? = 'INDEXING' AND index_attempts < 3
                            THEN CURRENT_TIMESTAMP + INTERVAL '1 minute' * index_attempts ELSE NULL END
                    WHERE id = ?
                    """, originalLifecycle, originalLifecycle, truncate(exception.getMessage()), originalLifecycle, documentId);
            return;
        }
        // 新版本或重试成功（INDEXING/FAILED → READY）自动发布；ACTIVE/ARCHIVED 重建索引保持原生命周期
        if ("INDEXING".equals(originalLifecycle) || "FAILED".equals(originalLifecycle)) {
            try {
                publish(documentId);
            } catch (Exception exception) {
                org.slf4j.LoggerFactory.getLogger(AgentKnowledgeDocumentService.class)
                        .warn("Auto publish failed for document {}, stays READY: {}", documentId, exception.getMessage());
            }
        }
    }

    private List<Document> mineruChunks(byte[] bytes, StoredDocument stored,
                                        Map<String, Object> sourceMetadata, String documentId) {
        String markdown = mineruDocumentParser.parse(bytes, stored.filename());
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalStateException("MinerU returned no extractable content");
        }
        sourceMetadata.put("parser", "mineru");
        return textSplitter.splitStructuredMarkdown(List.of(Document.builder().id(documentId).text(markdown)
                .metadata(sourceMetadata).build()));
    }

    private String safeMessage(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value.replaceAll("[\\r\\n]", " ");
    }

    private List<Document> localChunks(byte[] bytes, StoredDocument stored,
                                       Map<String, Object> sourceMetadata, String documentId) {
        ParsedDocument parsed = documentParsingModule.parse(bytes, stored.filename());
        if (parsed.content() == null || parsed.content().isBlank()) {
            throw new IllegalArgumentException("PDF does not contain extractable text; OCR is required for scanned documents");
        }
        sourceMetadata.put("parser", parsed.parser());
        sourceMetadata.putAll(parsed.metadata());
        return textSplitter.splitStructuredMarkdown(List.of(Document.builder().id(documentId).text(parsed.content())
                .metadata(sourceMetadata).build()));
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
                       logical_key, version_no, lifecycle_status, content_sha256, published_at, archived_at, created_by,
                       category_id
                FROM agent_knowledge_document WHERE tenant_id = ? AND agent_key = ?
                ORDER BY logical_key, version_no DESC
                """, (rs, rowNum) -> mapDocument(rs), tenantId, agentKey);
    }

    public List<AgentKnowledgeCategory> listCategories(String tenantId, String agentKey) {
        validateScope(tenantId, agentKey);
        ensureDefaultCategory(tenantId, agentKey);
        return jdbcTemplate.query("""
                SELECT id, tenant_id, agent_key, name, created_at, created_by
                FROM agent_knowledge_category
                WHERE tenant_id = ? AND agent_key = ?
                ORDER BY CASE WHEN name = ? THEN 0 ELSE 1 END, created_at, name
                """, (rs, rowNum) -> new AgentKnowledgeCategory(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("agent_key"), rs.getString("name"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("created_by")), tenantId, agentKey, DEFAULT_CATEGORY_NAME);
    }

    public AgentKnowledgeCategory createCategory(String tenantId, String agentKey, String name, String createdBy) {
        validateScope(tenantId, agentKey);
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty() || normalized.length() > 40) {
            throw new IllegalArgumentException("分类名称长度需为 1-40 个字符");
        }
        ensureDefaultCategory(tenantId, agentKey);
        String id = UUID.randomUUID().toString();
        try {
            jdbcTemplate.update("""
                    INSERT INTO agent_knowledge_category (id, tenant_id, agent_key, name, created_by)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, tenantId, agentKey, normalized, createdBy);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new IllegalArgumentException("分类已存在");
        }
        return new AgentKnowledgeCategory(id, tenantId, agentKey, normalized, java.time.Instant.now(), createdBy);
    }

    public AgentKnowledgeDocument moveDocument(String tenantId, String agentKey, String documentId, String categoryId) {
        validateScope(tenantId, agentKey);
        String resolvedCategoryId = resolveCategoryId(tenantId, agentKey, categoryId);
        int updated = jdbcTemplate.update("""
                UPDATE agent_knowledge_document SET category_id = ?
                WHERE id = ? AND tenant_id = ? AND agent_key = ?
                """, resolvedCategoryId, documentId, tenantId, agentKey);
        if (updated == 0) throw new IllegalArgumentException("Document does not exist in the current scope");
        return getDocumentRecord(tenantId, agentKey, documentId);
    }

    public AgentKnowledgeDocumentDetail getDocument(String tenantId, String agentKey, String documentId) {
        validateScope(tenantId, agentKey);
        StoredDocument stored = queryStoredDocument(documentId, true);
        String sourceContent = "";
        try (var object = ossClientProvider.getClient().getObject(bucket, stored.objectKey()); var input = object.getObjectContent()) {
            sourceContent = documentParsingModule.parse(input.readAllBytes(), stored.filename()).content();
        } catch (Exception exception) {
            org.slf4j.LoggerFactory.getLogger(AgentKnowledgeDocumentService.class)
                    .warn("Unable to render source content for document detail; filename={}, parser={}, reason={}",
                            stored.filename(), doclingChunkingService.shouldUse(stored.filename()) ? "docling-serve" : "local",
                            exception.getMessage());
        }
        List<AgentKnowledgeChunk> chunks = jdbcTemplate.query("""
                SELECT id::text, content, NULLIF(metadata ->> 'chunkIndex', '')::integer AS chunk_index
                FROM agent_knowledge_vector
                WHERE metadata ->> 'documentId' = ? AND metadata ->> 'tenantId' = ? AND metadata ->> 'agentKey' = ?
                ORDER BY chunk_index NULLS LAST, id
                """, (rs, rowNum) -> new AgentKnowledgeChunk(rs.getString("id"),
                rs.getObject("chunk_index", Integer.class) == null ? rowNum + 1 : rs.getInt("chunk_index"), rs.getString("content")),
                documentId, tenantId, agentKey);
        if (sourceContent.isBlank() && !chunks.isEmpty()) {
            sourceContent = chunks.stream().map(AgentKnowledgeChunk::content)
                    .collect(java.util.stream.Collectors.joining("\n\n"));
        }
        return new AgentKnowledgeDocumentDetail(documentId, stored.filename(), stored.objectKey(), sourceContent, chunks);
    }

    /**
     * 按持久化切片 ID 读取引用原文，用于回答下方“引用资料”预览。
     * 只校验 tenant/agent 作用域，不要求管理员权限；引用来自当前用户可见的回答。
     */
    public java.util.Optional<AgentKnowledgeChunkPreview> getChunk(String tenantId, String agentKey, String chunkId) {
        validateScope(tenantId, agentKey);
        return jdbcTemplate.query("""
                SELECT id::text AS chunk_id, content,
                       metadata ->> 'documentId' AS document_id,
                       metadata ->> 'filename' AS filename,
                       NULLIF(metadata ->> 'chunkIndex', '')::integer AS chunk_index
                FROM agent_knowledge_vector
                WHERE id::text = ? AND metadata ->> 'tenantId' = ? AND metadata ->> 'agentKey' = ?
                """, (rs, rowNum) -> new AgentKnowledgeChunkPreview(
                        rs.getString("chunk_id"), rs.getString("document_id"), rs.getString("filename"),
                        rs.getObject("chunk_index", Integer.class), rs.getString("content")),
                chunkId, tenantId, agentKey).stream().findFirst();
    }

    /** 读取原始文件字节，供前端在浏览器内解析预览。 */
    public DocumentFile readDocumentFile(String tenantId, String agentKey, String documentId) {
        validateScope(tenantId, agentKey);
        StoredDocument stored = queryStoredDocument(documentId, true);
        if (!tenantId.equals(stored.tenantId()) || !agentKey.equals(stored.agentKey())) {
            throw new IllegalArgumentException("Document does not exist in the current scope");
        }
        try (var object = ossClientProvider.getClient().getObject(bucket, stored.objectKey());
             var input = object.getObjectContent()) {
            return new DocumentFile(input.readAllBytes(), stored.filename(), contentType(stored.filename()));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read knowledge document", exception);
        }
    }

    private static String contentType(String filename) {
        String extension = filename == null || !filename.contains(".")
                ? "" : filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "md" -> "text/markdown; charset=UTF-8";
            case "txt" -> "text/plain; charset=UTF-8";
            default -> "application/octet-stream";
        };
    }

    public AgentKnowledgeDocument getDocumentRecordForScope(String tenantId, String agentKey, String documentId) {
        validateScope(tenantId, agentKey);
        return getDocumentRecord(tenantId, agentKey, documentId);
    }

    private AgentKnowledgeDocument getDocumentRecord(String tenantId, String agentKey, String id) {
        return jdbcTemplate.queryForObject("""
                SELECT id, tenant_id, agent_key, filename, status, chunk_count, error_message, created_at, completed_at,
                       logical_key, version_no, lifecycle_status, content_sha256, published_at, archived_at, created_by,
                       category_id
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
                rs.getString("created_by"), rs.getString("category_id"));
    }

    private Document toScopedChunk(Document document, StoredDocument stored, int chunkIndex) {
        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        metadata.put("documentId", stored.documentId());
        metadata.putIfAbsent("filename", stored.filename());
        metadata.put("tenantId", stored.tenantId());
        metadata.put("agentKey", stored.agentKey());
        metadata.put("logicalKey", stored.logicalKey());
        metadata.put("versionNo", stored.versionNo());
        metadata.put("lifecycleStatus", stored.lifecycleStatus());
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("parentDocumentId", stored.documentId());
        metadata.put("parentLogicalKey", stored.logicalKey());
        if (chunkIndex > 1) metadata.put("previousChunkIndex", chunkIndex - 1);
        return Document.builder().id(UUID.nameUUIDFromBytes((stored.documentId() + "|chunk|" + chunkIndex).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString())
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

    private String resolveCategoryId(String tenantId, String agentKey, String categoryId) {
        if (categoryId != null && !categoryId.isBlank()) {
            List<String> ids = jdbcTemplate.queryForList("""
                    SELECT id FROM agent_knowledge_category
                    WHERE id = ? AND tenant_id = ? AND agent_key = ?
                    """, String.class, categoryId, tenantId, agentKey);
            if (ids.isEmpty()) throw new IllegalArgumentException("分类不存在");
            return ids.get(0);
        }
        return defaultCategoryId(tenantId, agentKey);
    }

    private String defaultCategoryId(String tenantId, String agentKey) {
        ensureDefaultCategory(tenantId, agentKey);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM agent_knowledge_category
                WHERE tenant_id = ? AND agent_key = ? AND name = ?
                """, String.class, tenantId, agentKey, DEFAULT_CATEGORY_NAME);
    }

    private void ensureDefaultCategory(String tenantId, String agentKey) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_knowledge_category
                WHERE tenant_id = ? AND agent_key = ? AND name = ?
                """, Integer.class, tenantId, agentKey, DEFAULT_CATEGORY_NAME);
        if (count != null && count > 0) return;
        try {
            jdbcTemplate.update("""
                    INSERT INTO agent_knowledge_category (id, tenant_id, agent_key, name, created_by)
                    VALUES (?, ?, ?, ?, 'system')
                    """, UUID.randomUUID().toString(), tenantId, agentKey, DEFAULT_CATEGORY_NAME);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // 并发创建时以已存在的行为准
        }
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
    public record DocumentFile(byte[] bytes, String filename, String contentType) { }

    private record StoredDocument(String documentId, String tenantId, String agentKey, String objectKey, String filename,
                                  String logicalKey, Integer versionNo, String lifecycleStatus) { }
}
