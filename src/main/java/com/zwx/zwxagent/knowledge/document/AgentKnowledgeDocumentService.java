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
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.zwx.zwxagent.knowledge.document.AgentKnowledgeChunkPreview.TableRegion;

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
    private final MineruBlockParser mineruBlockParser;
    private final StructuredKnowledgeChunker structuredKnowledgeChunker;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final String documentParserProvider;
    private final TransactionTemplate txTemplate;
    private final AgentKnowledgeReviewLogService reviewLogService;
    private final N8nResumeNotifier resumeNotifier;
    private final N8nReviewEventNotifier reviewEventNotifier;

    // 向量批间并行度：默认 1（串行）。DashScope embedding 配额（TPM）是真实瓶颈，
    // 并行只会提前触发 429 Throttling.AllocationQuota 反而更慢（2026-10-25 实测：4 路并行 125s vs 串行 ~80s）；
    // 配额充足的账号可调大。字段注入避免构造器继续加长，手工构造的单测取初始值。
    @Value("${app.knowledge.index.embedding-parallelism:1}")
    int embeddingParallelism = 1;

    public AgentKnowledgeDocumentService(OssClientProvider ossClientProvider,
                                         @Value("${app.oss.bucket}") String bucket, JdbcTemplate jdbcTemplate,
                                         @Qualifier("agentKnowledgeVectorStore") VectorStore vectorStore,
                                         MyTokenTextSplitter textSplitter, DocumentParsingModule documentParsingModule,
                                         DoclingServeChunkingService doclingChunkingService,
                                         MineruDocumentParser mineruDocumentParser,
                                         MineruBlockParser mineruBlockParser,
                                         StructuredKnowledgeChunker structuredKnowledgeChunker,
                                         com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                         @Value("${app.document-parser.provider:mineru}") String documentParserProvider,
                                         PlatformTransactionManager transactionManager,
                                         AgentKnowledgeReviewLogService reviewLogService,
                                         N8nResumeNotifier resumeNotifier,
                                         N8nReviewEventNotifier reviewEventNotifier) {
        this.ossClientProvider = ossClientProvider;
        this.bucket = bucket;
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
        this.documentParsingModule = documentParsingModule;
        this.doclingChunkingService = doclingChunkingService;
        this.mineruDocumentParser = mineruDocumentParser;
        this.mineruBlockParser = mineruBlockParser;
        this.structuredKnowledgeChunker = structuredKnowledgeChunker;
        this.objectMapper = objectMapper;
        this.documentParserProvider = documentParserProvider == null || documentParserProvider.isBlank()
                ? "mineru" : documentParserProvider.trim().toLowerCase(java.util.Locale.ROOT);
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.reviewLogService = reviewLogService;
        this.resumeNotifier = resumeNotifier;
        this.reviewEventNotifier = reviewEventNotifier;
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
        return upload(tenantId, agentKey, file, createdBy, categoryId, true);
    }

    public AgentKnowledgeDocument upload(String tenantId, String agentKey, MultipartFile file, String createdBy,
                                         String categoryId, boolean autoPublish) {
        return upload(tenantId, agentKey, file, createdBy, categoryId, autoPublish, null);
    }

    /**
     * 携带幂等键的重试必须返回首次创建的文档而不是重复建版；键唯一约束由 V22 部分唯一索引兜底。
     */
    public AgentKnowledgeDocument upload(String tenantId, String agentKey, MultipartFile file, String createdBy,
                                         String categoryId, boolean autoPublish, String idempotencyKey) {
        validateScope(tenantId, agentKey);
        String normalizedKey = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (normalizedKey != null) {
            AgentKnowledgeDocument existing = findByUploadIdempotencyKey(tenantId, agentKey, normalizedKey);
            if (existing != null) return existing;
        }
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
        try {
            jdbcTemplate.update("""
                INSERT INTO agent_knowledge_document
                    (id, tenant_id, agent_key, object_key, filename, status,
                     logical_key, version_no, lifecycle_status, content_sha256, supersedes_document_id, created_by,
                     category_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, 'INDEXING', ?, ?, ?, ?, ?)
                """, id, tenantId, agentKey, objectKey, filename, logicalKey, nextVersion,
                sha256(content), supersedesId, createdBy, resolvedCategoryId, normalizedKey);
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            AgentKnowledgeDocument existing = findByUploadIdempotencyKey(tenantId, agentKey, normalizedKey);
            if (existing != null) return existing;
            throw exception;
        }
        AgentKnowledgeDocument document = getDocumentRecord(tenantId, agentKey, id);
        if (!autoPublish) {
            jdbcTemplate.update("UPDATE agent_knowledge_document SET auto_publish = FALSE WHERE id = ?", id);
        }
        // 案件在上传时即打开（n8n 侧随后登记阶段恢复地址，索引完成后由后端唤醒回填三阶段指标）
        if (normalizedKey == null || !normalizedKey.startsWith("n8n-")) {
            reviewEventNotifier.dispatchAsync(id, tenantId, agentKey, filename, nextVersion, createdBy);
        }
        return document;
    }

    private AgentKnowledgeDocument findByUploadIdempotencyKey(String tenantId, String agentKey, String idempotencyKey) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM agent_knowledge_document
                WHERE tenant_id = ? AND agent_key = ? AND idempotency_key = ?
                ORDER BY created_at LIMIT 1
                """, (rs, rowNum) -> rs.getString("id"), tenantId, agentKey, idempotencyKey);
        return ids.isEmpty() ? null : getDocumentRecord(tenantId, agentKey, ids.get(0));
    }

    public void deleteDocument(String tenantId, String agentKey, String documentId) {
        validateScope(tenantId, agentKey);
        StoredDocument stored;
        try {
            stored = queryStoredDocument(documentId, true);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw new IllegalArgumentException("Document does not exist in the current scope");
        }
        removeDocumentCompletely(documentId, stored.tenantId(), agentKey);
    }

    void removeDocumentCompletely(String documentId, String tenantId, String agentKey) {
        List<String> objectKeys = jdbcTemplate.queryForList(
                "SELECT object_key FROM agent_knowledge_document WHERE id = ?", String.class, documentId);
        List<String> chunkIds = jdbcTemplate.queryForList("""
                SELECT id::text FROM agent_knowledge_vector
                WHERE metadata ->> 'documentId' = ?
                """, String.class, documentId);
        if (!chunkIds.isEmpty()) vectorStore.delete(chunkIds);
        jdbcTemplate.update("DELETE FROM agent_knowledge_block WHERE document_id = ?", documentId);
        jdbcTemplate.update("DELETE FROM agent_knowledge_document WHERE id = ?", documentId);
        for (String objectKey : objectKeys) {
            try {
                ossClientProvider.getClient().deleteObject(bucket, objectKey);
            } catch (Exception ignored) {
            }
        }
        // MinerU 结果包按 documentId 目录保存，原文件不在该目录下，需要按前缀一并清理。
        try {
            deleteObjectsByPrefix("knowledge/" + tenantId + "/" + agentKey + "/" + documentId + "/");
        } catch (Exception exception) {
            log.warn("Unable to clean MinerU result objects; documentId={}, reason={}", documentId, safeMessage(exception));
        }
    }

    private void deleteObjectsByPrefix(String prefix) {
        String marker = null;
        while (true) {
            com.aliyun.oss.model.ListObjectsRequest request = new com.aliyun.oss.model.ListObjectsRequest(bucket)
                    .withPrefix(prefix)
                    .withMaxKeys(1000);
            if (marker != null && !marker.isBlank()) request.setMarker(marker);
            com.aliyun.oss.model.ObjectListing listing = ossClientProvider.getClient().listObjects(request);
            for (com.aliyun.oss.model.OSSObjectSummary summary : listing.getObjectSummaries()) {
                try {
                    ossClientProvider.getClient().deleteObject(bucket, summary.getKey());
                } catch (Exception ignored) {
                }
            }
            if (!listing.isTruncated()) break;
            marker = listing.getNextMarker();
            if (marker == null || marker.isBlank()) break;
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
        long indexStartedAt = System.currentTimeMillis();
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
            long parseMs = System.currentTimeMillis() - indexStartedAt;
            log.info("knowledge index parse phase done: documentId={}, chunks={}, parser={}, took={}ms",
                    documentId, chunks.size(), sourceMetadata.get("parser"), parseMs);
            long vectorMs = storeVectors(chunks);
            jdbcTemplate.update("""
                    UPDATE agent_knowledge_document SET status = 'READY',
                        lifecycle_status = CASE WHEN ? IN ('INDEXING', 'FAILED') THEN 'READY' ELSE ? END,
                        error_message = NULL, next_retry_at = NULL,
                        chunk_count = ?, parse_duration_ms = ?, vector_duration_ms = ?,
                        completed_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, originalLifecycle, originalLifecycle, chunks.size(), (int) parseMs, (int) vectorMs, documentId);
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
        boolean autoPublish = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT auto_publish FROM agent_knowledge_document WHERE id = ?", Boolean.class, documentId));
        if (autoPublish && ("INDEXING".equals(originalLifecycle) || "FAILED".equals(originalLifecycle))) {
            try {
                publish(documentId);
            } catch (Exception exception) {
                org.slf4j.LoggerFactory.getLogger(AgentKnowledgeDocumentService.class)
                        .warn("Auto publish failed for document {}, stays READY: {}", documentId, exception.getMessage());
            }
        } else if (!autoPublish) {
            notifyPendingReview(documentId);
        }
    }

    /** 待审提交统一出口：SUBMIT 审计 + 唤醒 n8n 案件回填索引阶段指标；n8n ingestion 上传（幂等键 n8n-*）有自己的工作流，不重复开案件。 */
    void notifyPendingReview(String documentId) {
        String[] scope = jdbcTemplate.queryForObject("""
                SELECT tenant_id, agent_key, COALESCE(idempotency_key, '')
                FROM agent_knowledge_document WHERE id = ?
                """, (rs, rowNum) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, documentId);
        reviewLogService.record(scope[0], scope[1], documentId, "SUBMIT", null, "SYSTEM",
                "索引完成，等待人工审核", null);
        if (!scope[2].startsWith("n8n-")) {
            resumeNotifier.triggerAsync(documentId);
        }
    }

    /** 索引阶段指标，供 workflow-status 暴露给 n8n 案件回显三阶段耗时。 */
    public java.util.Map<String, Integer> indexMetrics(String documentId) {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(parse_duration_ms, 0), COALESCE(vector_duration_ms, 0), COALESCE(chunk_count, 0)
                FROM agent_knowledge_document WHERE id = ?
                """, (rs, rowNum) -> java.util.Map.of(
                "parseDurationMs", rs.getInt(1),
                "vectorDurationMs", rs.getInt(2),
                "chunkCount", rs.getInt(3)), documentId);
    }

    /**
     * 向量写入：embedding 按批调用远端 API，批间并行压缩大文档的索引墙钟时间；任一批失败即整体失败（与原串行语义一致）。
     *
     * @return 向量阶段耗时（毫秒），随 READY 一并持久化供 n8n 案件回显
     */
    long storeVectors(List<Document> chunks) {
        List<List<Document>> batches = new ArrayList<>();
        for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
            batches.add(chunks.subList(start, Math.min(start + BATCH_SIZE, chunks.size())));
        }
        long startedAt = System.currentTimeMillis();
        if (batches.size() <= 1 || embeddingParallelism <= 1) {
            batches.forEach(vectorStore::add);
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(embeddingParallelism, runnable -> {
                Thread thread = new Thread(runnable, "knowledge-embedding");
                thread.setDaemon(true);
                return thread;
            });
            try {
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>(batches.size());
                for (List<Document> batch : batches) futures.add(pool.submit(() -> vectorStore.add(batch)));
                for (java.util.concurrent.Future<?> future : futures) future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
                throw new IllegalStateException("Vector indexing interrupted", interrupted);
            } catch (java.util.concurrent.ExecutionException executionException) {
                pool.shutdownNow();
                Throwable cause = executionException.getCause() == null ? executionException : executionException.getCause();
                throw cause instanceof RuntimeException runtimeException ? runtimeException
                        : new IllegalStateException("Vector batch failed", cause);
            } finally {
                pool.shutdown();
            }
        }
        long vectorMs = System.currentTimeMillis() - startedAt;
        log.info("knowledge index vector phase done: chunks={}, batches={}, parallelism={}, took={}ms",
                chunks.size(), batches.size(), batches.size() <= 1 ? 1 : embeddingParallelism, vectorMs);
        return vectorMs;
    }

    private List<Document> mineruChunks(byte[] bytes, StoredDocument stored,
                                        Map<String, Object> sourceMetadata, String documentId) {
        MineruParseResult result = mineruDocumentParser.parse(bytes, stored.filename());
        String markdown = result.markdown();
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalStateException("MinerU returned no extractable content");
        }
        sourceMetadata.put("parser", "mineru");
        sourceMetadata.put("mineruResultFileCount", result.fileNames().size());
        sourceMetadata.put("mineruJsonFileCount", result.countFiles(".json"));
        sourceMetadata.put("mineruHtmlFileCount", result.countFiles(".html"));

        List<MineruBlockParser.MineruBlock> blocks = mineruBlockParser.parse(result);
        if (!blocks.isEmpty()) {
            Map<String, String> objectKeys = persistMineruFiles(stored, result, documentId);
            persistBlocks(stored, documentId, blocks, objectKeys);
            List<Document> structured = structuredKnowledgeChunker.chunks(blocks, sourceMetadata);
            if (!structured.isEmpty()) return structured;
            log.warn("MinerU content_list produced no usable chunks, falling back to markdown; filename={}", stored.filename());
        }
        return textSplitter.splitStructuredMarkdown(List.of(Document.builder().id(documentId).text(markdown)
                .metadata(sourceMetadata).build()));
    }

    /** 完整结果包落 OSS，图片块可以直接按相对路径找到对象。失败不阻断 Markdown 索引。 */
    private Map<String, String> persistMineruFiles(StoredDocument stored, MineruParseResult result, String documentId) {
        Map<String, String> objectKeys = new HashMap<>();
        String base = "knowledge/" + stored.tenantId() + "/" + stored.agentKey() + "/" + documentId + "/mineru/";
        for (Map.Entry<String, byte[]> entry : result.files().entrySet()) {
            String name = safeObjectName(entry.getKey());
            if (name.isBlank()) continue;
            String key = base + name;
            try {
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentLength(entry.getValue().length);
                metadata.setContentType(contentType(name));
                ossClientProvider.getClient().putObject(bucket, key, new ByteArrayInputStream(entry.getValue()), metadata);
                objectKeys.put(entry.getKey(), key);
            } catch (Exception exception) {
                log.warn("Unable to persist MinerU result file; documentId={}, file={}, reason={}",
                        documentId, name, safeMessage(exception));
            }
        }
        String manifestKey = base + "manifest.json";
        try {
            byte[] manifest = objectMapper.writeValueAsBytes(Map.of(
                    "files", result.fileNames(),
                    "jsonFileCount", result.countFiles(".json"),
                    "htmlFileCount", result.countFiles(".html"),
                    "capturedAt", java.time.Instant.now().toString()));
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(manifest.length);
            metadata.setContentType("application/json; charset=UTF-8");
            ossClientProvider.getClient().putObject(bucket, manifestKey, new ByteArrayInputStream(manifest), metadata);
        } catch (Exception exception) {
            log.warn("Unable to persist MinerU manifest; documentId={}, reason={}", documentId, safeMessage(exception));
        }
        return objectKeys;
    }

    private void persistBlocks(StoredDocument stored, String documentId,
                               List<MineruBlockParser.MineruBlock> blocks, Map<String, String> objectKeys) {
        jdbcTemplate.update("DELETE FROM agent_knowledge_block WHERE document_id = ?", documentId);
        for (MineruBlockParser.MineruBlock block : blocks) {
            String json = null;
            if (AgentKnowledgeBlock.TABLE.equals(block.type()) && block.table() != null) {
                try {
                    json = objectMapper.writeValueAsString(Map.of(
                            "caption", block.table().caption() == null ? "" : block.table().caption(),
                            "headers", block.table().headers(),
                            "rows", block.table().rows()));
                } catch (Exception exception) {
                    log.warn("Unable to serialize knowledge table block; documentId={}, blockId={}", documentId, block.blockId());
                }
            }
            String imageObjectKey = block.imagePath() == null ? null : resolveObjectKey(objectKeys, block.imagePath());
            jdbcTemplate.update("""
                    INSERT INTO agent_knowledge_block
                        (id, document_id, tenant_id, agent_key, block_id, source_type, page_number, bbox, caption,
                         text_content, html_content, json_content, image_object_key)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, CAST(? AS jsonb), ?)
                    """, UUID.randomUUID().toString(), documentId, stored.tenantId(), stored.agentKey(),
                    block.blockId(), block.type(), block.pageNumber(), block.bbox(), block.caption(),
                    block.text(), block.html(), json, imageObjectKey);
        }
    }

    private String resolveObjectKey(Map<String, String> objectKeys, String imagePath) {
        String exact = objectKeys.get(imagePath);
        if (exact != null) return exact;
        String normalized = imagePath.replace('\\', '/');
        return objectKeys.entrySet().stream()
                .filter(entry -> entry.getKey().replace('\\', '/').endsWith(normalized))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private String safeObjectName(String name) {
        if (name == null) return "";
        String normalized = name.replace('\\', '/').replaceAll("^/+", "");
        while (normalized.contains("../")) normalized = normalized.replace("../", "");
        return normalized.replaceAll("[^A-Za-z0-9._/-]", "_");
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
        publish(documentId, null, "SYSTEM");
    }

    /**
     * 审批发布：状态更新与审计行同一事务；事务提交后异步触发 n8n 收尾恢复。
     */
    public void publish(String documentId, com.zwx.zwxagent.platform.security.CurrentActor actor, String channel) {
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
            reviewLogService.record(stored.tenantId(), stored.agentKey(), documentId, "PUBLISH",
                    actor, channel, null, null);
        });
        resumeNotifier.triggerAsync(documentId);
    }

    public void reject(String documentId, String reviewNote) {
        reject(documentId, reviewNote, null, "SYSTEM");
    }

    public void reject(String documentId, String reviewNote, com.zwx.zwxagent.platform.security.CurrentActor actor,
                       String channel) {
        StoredDocument stored = queryStoredDocument(documentId, false);
        txTemplate.executeWithoutResult(transactionStatus -> {
            int updated = jdbcTemplate.update("""
                    UPDATE agent_knowledge_document
                    SET status = 'REJECTED', lifecycle_status = 'REJECTED', review_note = ?
                    WHERE id = ? AND lifecycle_status = 'READY'
                    """, reviewNote == null ? "" : reviewNote.trim(), documentId);
            if (updated == 0) throw new IllegalStateException("Only READY versions can be rejected");
            flipChunkLifecycle(documentId, "REJECTED");
            reviewLogService.record(stored.tenantId(), stored.agentKey(), documentId, "REJECT",
                    actor, channel, reviewNote == null ? "" : reviewNote.trim(), null);
        });
        resumeNotifier.triggerAsync(documentId);
    }

    /**
     * n8n 在暂停到人工审核前登记恢复地址；URL 属恢复凭据，仅后端持有，不回显。
     */
    public void storeResumeInfo(String tenantId, String agentKey, String documentId, String resumeUrl,
                                String n8nExecutionId, java.time.Instant expiresAt) {
        validateScope(tenantId, agentKey);
        int updated = jdbcTemplate.update("""
                UPDATE agent_knowledge_document
                SET resume_webhook_url = ?, resume_expires_at = ?
                WHERE id = ? AND tenant_id = ? AND agent_key = ?
                """, resumeUrl, expiresAt, documentId, tenantId, agentKey);
        if (updated == 0) throw new IllegalArgumentException("Document does not exist in the current scope");
    }

    public void recordReviewEvent(String tenantId, String agentKey, String documentId, String action,
                                  com.zwx.zwxagent.platform.security.CurrentActor actor, String channel,
                                  String note, String n8nExecutionId) {
        getDocumentRecordForScope(tenantId, agentKey, documentId);
        reviewLogService.record(tenantId, agentKey, documentId, action, actor, channel, note, n8nExecutionId);
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
                       category_id, review_note
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
        return getChunk(tenantId, agentKey, chunkId, null, null);
    }

    /**
     * 按切片 ID 读取引用；切片已被重建时，可用 documentId + blockId 回退到来源块。
     */
    public java.util.Optional<AgentKnowledgeChunkPreview> getChunk(String tenantId, String agentKey, String chunkId,
                                                                    String documentId, String blockId) {
        validateScope(tenantId, agentKey);
        java.util.Optional<AgentKnowledgeChunkPreview> chunk = jdbcTemplate.query("""
                SELECT id::text AS chunk_id, content,
                       metadata ->> 'documentId' AS document_id,
                       metadata ->> 'filename' AS filename,
                       NULLIF(metadata ->> 'chunkIndex', '')::integer AS chunk_index,
                       NULLIF(metadata ->> 'pageNumber', '')::integer AS page_number,
                       metadata ->> 'sectionPath' AS section_path,
                       metadata ->> 'sourceType' AS source_type,
                       metadata ->> 'blockId' AS block_id,
                       NULLIF(metadata ->> 'rowStart', '')::integer AS row_start,
                       NULLIF(metadata ->> 'rowEnd', '')::integer AS row_end,
                       metadata ->> 'regionLabel' AS region_label,
                       metadata ->> 'tableCaption' AS table_caption
                FROM agent_knowledge_vector
                WHERE id::text = ? AND metadata ->> 'tenantId' = ? AND metadata ->> 'agentKey' = ?
                """, (rs, rowNum) -> chunkPreview(tenantId, agentKey,
                        rs.getString("chunk_id"), rs.getString("document_id"), rs.getString("filename"),
                        rs.getObject("chunk_index", Integer.class), rs.getString("content"),
                        rs.getObject("page_number", Integer.class), rs.getString("section_path"),
                        rs.getString("source_type"), rs.getString("block_id"),
                        rs.getObject("row_start", Integer.class), rs.getObject("row_end", Integer.class),
                        rs.getString("region_label"), rs.getString("table_caption")),
                chunkId, tenantId, agentKey).stream().findFirst();
        if (chunk.isPresent()) return chunk;
        if (documentId == null || documentId.isBlank() || blockId == null || blockId.isBlank()) {
            return java.util.Optional.empty();
        }
        return jdbcTemplate.query("""
                SELECT b.*, d.filename
                FROM agent_knowledge_block b
                JOIN agent_knowledge_document d ON d.id = b.document_id
                WHERE b.document_id = ? AND b.block_id = ? AND b.tenant_id = ? AND b.agent_key = ?
                """, (rs, rowNum) -> blockPreview(tenantId, agentKey,
                        rs.getString("document_id"), rs.getString("filename"), rs.getString("block_id"),
                        rs.getString("source_type"), rs.getObject("page_number", Integer.class),
                        rs.getString("caption"), rs.getString("text_content"), rs.getString("json_content"),
                        rs.getString("image_object_key")),
                documentId, blockId, tenantId, agentKey).stream().findFirst();
    }

    private AgentKnowledgeChunkPreview chunkPreview(String tenantId, String agentKey, String chunkId, String documentId,
                                                    String filename, Integer chunkIndex, String content,
                                                    Integer pageNumber, String sectionPath, String sourceType,
                                                    String blockId, Integer rowStart, Integer rowEnd,
                                                    String regionLabel, String tableCaption) {
        AgentKnowledgeBlock block = null;
        if (documentId != null && blockId != null) {
            block = findBlock(tenantId, agentKey, documentId, blockId);
        }
        TableRegion table = tableRegion(sourceType, block, rowStart, rowEnd, tableCaption);
        boolean legacy = sourceType == null || sourceType.isBlank();
        return new AgentKnowledgeChunkPreview(chunkId, documentId, filename, chunkIndex, content,
                plainText(content), pageNumber, sectionPath, sourceType, blockId,
                regionLabel == null || regionLabel.isBlank() ? legacyLabel(filename, pageNumber) : regionLabel,
                legacy, table);
    }

    private AgentKnowledgeChunkPreview blockPreview(String tenantId, String agentKey, String documentId,
                                                    String filename, String blockId, String sourceType,
                                                    Integer pageNumber, String caption, String textContent,
                                                    String jsonContent, String imageObjectKey) {
        AgentKnowledgeBlock block = new AgentKnowledgeBlock(null, documentId, tenantId, agentKey, blockId, sourceType,
                pageNumber, null, caption, textContent, null, jsonContent, imageObjectKey, null);
        String content = textContent == null || textContent.isBlank() ? caption : textContent;
        TableRegion table = tableRegion(sourceType, block, null, null, caption);
        return new AgentKnowledgeChunkPreview(null, documentId, filename, null, content,
                plainText(content == null ? "" : content), pageNumber, null, sourceType, blockId,
                caption == null || caption.isBlank() ? legacyLabel(filename, pageNumber) : caption,
                false, table);
    }

    private AgentKnowledgeBlock findBlock(String tenantId, String agentKey, String documentId, String blockId) {
        return jdbcTemplate.query("""
                        SELECT * FROM agent_knowledge_block
                        WHERE document_id = ? AND block_id = ? AND tenant_id = ? AND agent_key = ?
                        """, (rs, rowNum) -> new AgentKnowledgeBlock(
                        rs.getString("id"), rs.getString("document_id"), rs.getString("tenant_id"),
                        rs.getString("agent_key"), rs.getString("block_id"), rs.getString("source_type"),
                        rs.getObject("page_number", Integer.class), rs.getString("bbox"),
                        rs.getString("caption"), rs.getString("text_content"), rs.getString("html_content"),
                        rs.getString("json_content"), rs.getString("image_object_key"),
                        rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()),
                documentId, blockId, tenantId, agentKey).stream().findFirst().orElse(null);
    }

    private TableRegion tableRegion(String sourceType, AgentKnowledgeBlock block, Integer rowStart, Integer rowEnd,
                                    String fallbackCaption) {
        if (!AgentKnowledgeBlock.TABLE.equals(sourceType) || block == null
                || block.jsonContent() == null || block.jsonContent().isBlank()) {
            return null;
        }
        try {
            var node = objectMapper.readTree(block.jsonContent());
            List<String> headers = new ArrayList<>();
            node.path("headers").forEach(item -> headers.add(item.asText("")));
            List<List<String>> rows = new ArrayList<>();
            node.path("rows").forEach(rowNode -> {
                List<String> row = new ArrayList<>();
                rowNode.forEach(cell -> row.add(cell.asText("")));
                rows.add(row);
            });
            if (rows.isEmpty()) return null;
            int from = rowStart == null ? 0 : Math.max(0, rowStart);
            int to = rowEnd == null ? rows.size() : Math.min(rows.size(), rowEnd);
            List<Integer> highlightRows = new ArrayList<>();
            for (int index = from; index < to; index++) highlightRows.add(index);
            String caption = node.path("caption").asText("");
            if (caption.isBlank()) caption = fallbackCaption;
            return new TableRegion(caption, headers, rows, highlightRows);
        } catch (Exception exception) {
            log.warn("Unable to read knowledge table block; documentId={}, blockId={}", block.documentId(), block.blockId());
            return null;
        }
    }

    private String plainText(String content) {
        if (content == null || content.isBlank()) return "";
        String lower = content.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("<table") || lower.contains("<tr") || lower.contains("<td") || lower.contains("<p")) {
            return org.jsoup.Jsoup.parse(content).text().replaceAll("\\s+", " ").trim();
        }
        return content;
    }

    private String legacyLabel(String filename, Integer pageNumber) {
        List<String> parts = new ArrayList<>();
        if (filename != null && !filename.isBlank()) parts.add(filename);
        if (pageNumber != null) parts.add("第 " + pageNumber + " 页");
        return String.join(" · ", parts);
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
                       category_id, review_note
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
                rs.getString("created_by"), rs.getString("category_id"), rs.getString("review_note"));
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
