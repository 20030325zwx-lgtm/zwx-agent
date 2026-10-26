package com.zwx.zwxagent.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.chat.conversation.AgentConversationService;
import com.zwx.zwxagent.knowledge.document.AgentKnowledgeDocument;
import com.zwx.zwxagent.knowledge.document.AgentKnowledgeDocumentDetail;
import com.zwx.zwxagent.knowledge.document.AgentKnowledgeDocumentService;
import com.zwx.zwxagent.knowledge.document.AgentKnowledgeReviewLogService;
import com.zwx.zwxagent.knowledge.document.AgentReviewerAssignmentService;
import com.zwx.zwxagent.knowledge.retrieval.AgentKnowledgeRagService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.security.ReviewAccessGuard;
import jakarta.annotation.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 租户知识库与审批治理接口：上传/查询/切片预览/发布退回/重删/审核人管理与 RAG 人工判例。
 * 原 AiController knowledge 组拆分，路径保持不变。
 */
@RestController
@RequestMapping("/ai")
public class KnowledgeAdminController {

    @Resource
    private AgentKnowledgeDocumentService agentKnowledgeDocumentService;

    @Resource
    private ReviewAccessGuard reviewAccessGuard;

    @Resource
    private AgentReviewerAssignmentService reviewerAssignmentService;

    @Resource
    private AgentKnowledgeReviewLogService reviewLogService;

    @Resource
    private AgentKnowledgeRagService agentKnowledgeRagService;

    @Resource
    private AgentConversationService agentConversationService;

    @Resource
    private ObjectMapper objectMapper;

    @PostMapping(value = "/agent-knowledge/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AgentKnowledgeDocument uploadAgentKnowledgeDocument(CurrentActor actor, @RequestParam String agentKey,
                                                               @RequestPart("file") MultipartFile file,
                                                               @RequestParam(required = false) String categoryId,
                                                               @RequestParam(defaultValue = "true") boolean autoPublish,
                                                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        actor.requireKnowledgeWorkflowAccess(agentKey);
        AgentKnowledgeDocument document = agentKnowledgeDocumentService.upload(actor.tenantId(), agentKey, file,
                actor.username(), categoryId, autoPublish, normalizeIdempotencyKey(idempotencyKey));
        agentKnowledgeDocumentService.indexDocument(document.id());
        return document;
    }

    private String normalizeIdempotencyKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String key = raw.trim();
        if (!key.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Idempotency-Key must be 1-64 characters of letters, digits, _ or -");
        return key;
    }

    @GetMapping("/agent-knowledge/documents")
    public List<AgentKnowledgeDocument> listAgentKnowledgeDocuments(CurrentActor actor, @RequestParam String agentKey) {
        actor.requireKnowledgeWorkflowAccess(agentKey);
        return agentKnowledgeDocumentService.listDocuments(actor.tenantId(), agentKey);
    }

    @GetMapping("/agent-knowledge/categories")
    public List<com.zwx.zwxagent.knowledge.document.AgentKnowledgeCategory> listAgentKnowledgeCategories(CurrentActor actor,
                                                                                          @RequestParam String agentKey) {
        actor.requireKnowledgeWorkflowAccess(agentKey);
        return agentKnowledgeDocumentService.listCategories(actor.tenantId(), agentKey);
    }

    @PostMapping("/agent-knowledge/categories")
    public com.zwx.zwxagent.knowledge.document.AgentKnowledgeCategory createAgentKnowledgeCategory(
            CurrentActor actor, @RequestBody CreateKnowledgeCategoryRequest request) {
        actor.requireAdmin();
        return agentKnowledgeDocumentService.createCategory(actor.tenantId(), request.agentKey(), request.name(), actor.username());
    }

    @PostMapping("/agent-knowledge/documents/{documentId}/category")
    public AgentKnowledgeDocument moveAgentKnowledgeDocument(CurrentActor actor, @PathVariable String documentId,
                                                             @RequestParam String agentKey,
                                                             @RequestParam String categoryId) {
        actor.requireAdmin();
        return agentKnowledgeDocumentService.moveDocument(actor.tenantId(), agentKey, documentId, categoryId);
    }

    public record CreateKnowledgeCategoryRequest(String agentKey, String name) {
    }

    @GetMapping("/agent-knowledge/chunks/{chunkId}")
    public com.zwx.zwxagent.knowledge.document.AgentKnowledgeChunkPreview getAgentKnowledgeChunk(
            CurrentActor actor, @PathVariable String chunkId, @RequestParam String agentKey,
            @RequestParam(required = false) String documentId,
            @RequestParam(required = false) String blockId) {
        actor.requireKnowledgeWorkflowAccess(agentKey);
        return agentKnowledgeDocumentService.getChunk(actor.tenantId(), agentKey, chunkId, documentId, blockId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Knowledge chunk not found"));
    }

    @GetMapping("/agent-knowledge/documents/{documentId}")
    public AgentKnowledgeDocumentDetail getAgentKnowledgeDocument(CurrentActor actor, @PathVariable String documentId, @RequestParam String agentKey) {
        actor.requireKnowledgeWorkflowAccess(agentKey);
        return agentKnowledgeDocumentService.getDocument(actor.tenantId(), agentKey, documentId);
    }

    @GetMapping("/agent-knowledge/documents/{documentId}/workflow-status")
    public java.util.Map<String, Object> getWorkflowDocumentStatus(CurrentActor actor, @PathVariable String documentId,
                                                            @RequestParam String agentKey) {
        actor.requireKnowledgeWorkflowAccess(agentKey);
        var document = agentKnowledgeDocumentService.getDocumentRecordForScope(actor.tenantId(), agentKey, documentId);
        java.util.Map<String, Object> status = new java.util.LinkedHashMap<>(objectMapper.convertValue(document, java.util.Map.class));
        status.putAll(agentKnowledgeDocumentService.indexMetrics(documentId));
        return status;
    }

    @PostMapping("/agent-knowledge/documents/{documentId}/verify-retrieval")
    public Map<String, Object> verifyWorkflowDocumentRetrieval(CurrentActor actor, @PathVariable String documentId,
                                                               @RequestParam String agentKey,
                                                               @RequestBody Map<String, String> request) {
        actor.requireKnowledgeWorkflowAccess(agentKey);
        agentKnowledgeDocumentService.getDocumentRecordForScope(actor.tenantId(), agentKey, documentId);
        String query = request.getOrDefault("query", "").trim();
        if (query.isBlank()) throw new IllegalArgumentException("query is required");
        List<Map<String, Object>> matches = agentKnowledgeRagService.verifyDocument(actor.tenantId(), agentKey, documentId, query).stream()
                .map(document -> Map.<String, Object>of("content", document.getText(), "metadata", document.getMetadata(),
                        "score", document.getScore() == null ? 0.0 : document.getScore())).toList();
        return Map.of("matched", !matches.isEmpty(), "matches", matches);
    }

    @GetMapping("/agent-knowledge/documents/{documentId}/content")
    public ResponseEntity<byte[]> getAgentKnowledgeDocumentContent(CurrentActor actor, @PathVariable String documentId,
                                                                   @RequestParam String agentKey) {
        actor.requireAdmin();
        var content = agentKnowledgeDocumentService.readDocumentFile(actor.tenantId(), agentKey, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(content.filename(), StandardCharsets.UTF_8).build().toString())
                .body(content.bytes());
    }

    @PostMapping("/agent-knowledge/documents/{documentId}/publish")
    public AgentKnowledgeDocument publishAgentKnowledgeDocument(CurrentActor actor, @PathVariable String documentId,
                                                                 @RequestParam String agentKey) {
        reviewAccessGuard.requireReviewAccess(actor, agentKey);
        agentKnowledgeDocumentService.getDocumentRecordForScope(actor.tenantId(), agentKey, documentId);
        agentKnowledgeDocumentService.publish(documentId, actor, "API");
        return agentKnowledgeDocumentService.getDocumentRecordForScope(actor.tenantId(), agentKey, documentId);
    }

    @PostMapping("/agent-knowledge/documents/{documentId}/reject")
    public AgentKnowledgeDocument rejectAgentKnowledgeDocument(CurrentActor actor, @PathVariable String documentId,
                                                                @RequestParam String agentKey,
                                                                @RequestBody(required = false) Map<String, String> request) {
        reviewAccessGuard.requireReviewAccess(actor, agentKey);
        agentKnowledgeDocumentService.getDocumentRecordForScope(actor.tenantId(), agentKey, documentId);
        agentKnowledgeDocumentService.reject(documentId, request == null ? "" : request.get("reviewNote"), actor, "API");
        return agentKnowledgeDocumentService.getDocumentRecordForScope(actor.tenantId(), agentKey, documentId);
    }

    @GetMapping("/agent-knowledge/documents/{documentId}/review-log")
    public List<com.zwx.zwxagent.knowledge.document.AgentKnowledgeReviewLogService.ReviewLogEntry> getReviewLog(
            CurrentActor actor, @PathVariable String documentId, @RequestParam String agentKey) {
        reviewAccessGuard.requireReviewAccess(actor, agentKey);
        agentKnowledgeDocumentService.getDocumentRecordForScope(actor.tenantId(), agentKey, documentId);
        return reviewLogService.listByDocument(actor.tenantId(), agentKey, documentId);
    }

    /**
     * n8n 人工审核暂停前登记恢复地址；恢复 URL 属机密，只接受登记，永不回显。
     */
    @PostMapping("/agent-knowledge/documents/{documentId}/resume-info")
    public Map<String, Object> storeResumeInfo(CurrentActor actor, @PathVariable String documentId,
                                               @RequestParam String agentKey,
                                               @RequestBody Map<String, Object> request) {
        actor.requireKnowledgeWorkflowAccess(agentKey);
        String resumeUrl = request.getOrDefault("resumeUrl", "").toString().trim();
        if (resumeUrl.isBlank()) throw new IllegalArgumentException("resumeUrl is required");
        String n8nExecutionId = request.getOrDefault("n8nExecutionId", "").toString().trim();
        String expiresAtRaw = request.getOrDefault("expiresAt", "").toString().trim();
        java.time.Instant expiresAt = expiresAtRaw.isBlank() ? null : java.time.Instant.parse(expiresAtRaw);
        agentKnowledgeDocumentService.storeResumeInfo(actor.tenantId(), agentKey, documentId, resumeUrl,
                n8nExecutionId.isBlank() ? null : n8nExecutionId, expiresAt);
        return Map.of("stored", true);
    }

    /**
     * n8n 侧追加审计事件（当前仅 TIMEOUT）；只记审计，不改变文档状态。
     */
    @PostMapping("/agent-knowledge/documents/{documentId}/review-events")
    public Map<String, Object> recordReviewEvent(CurrentActor actor, @PathVariable String documentId,
                                                 @RequestParam String agentKey,
                                                 @RequestBody Map<String, String> request) {
        actor.requireKnowledgeWorkflowAccess(agentKey);
        String action = request.getOrDefault("action", "").trim();
        if (!"TIMEOUT".equals(action)) {
            throw new IllegalArgumentException("Only TIMEOUT review events are accepted from workflow identities");
        }
        agentKnowledgeDocumentService.recordReviewEvent(actor.tenantId(), agentKey, documentId, action,
                actor, "N8N", request.getOrDefault("note", ""), request.getOrDefault("n8nExecutionId", ""));
        return Map.of("recorded", true);
    }

    @GetMapping("/agent-reviewers")
    public List<com.zwx.zwxagent.knowledge.document.AgentReviewerAssignmentService.ReviewerAssignment> listReviewers(
            CurrentActor actor, @RequestParam(required = false) String agentKey) {
        actor.requireAdmin();
        return reviewerAssignmentService.list(actor.tenantId(), agentKey);
    }

    public record ReviewerAssignRequest(String agentKey, String username) {
    }

    @PostMapping("/agent-reviewers")
    public com.zwx.zwxagent.knowledge.document.AgentReviewerAssignmentService.ReviewerAssignment assignReviewer(
            CurrentActor actor, @RequestBody ReviewerAssignRequest request) {
        actor.requireAdmin();
        if (request == null || request.agentKey() == null || request.agentKey().isBlank()
                || request.username() == null || request.username().isBlank()) {
            throw new IllegalArgumentException("agentKey and username are required");
        }
        return reviewerAssignmentService.assign(actor, actor.tenantId(), request.agentKey().trim(), request.username().trim());
    }

    @DeleteMapping("/agent-reviewers/{assignmentId}")
    public void unassignReviewer(CurrentActor actor, @PathVariable String assignmentId) {
        actor.requireAdmin();
        reviewerAssignmentService.unassign(assignmentId);
    }

    @PostMapping("/agent-knowledge/documents/{documentId}/reindex")
    public AgentKnowledgeDocument reindexAgentKnowledgeDocument(CurrentActor actor, @PathVariable String documentId, @RequestParam String agentKey) {
        actor.requireAdmin();
        AgentKnowledgeDocument document = agentKnowledgeDocumentService.getDocumentRecordForScope(actor.tenantId(), agentKey, documentId);
        agentKnowledgeDocumentService.indexDocument(documentId);
        return document;
    }

    @DeleteMapping("/agent-knowledge/documents/{documentId}")
    public void deleteAgentKnowledgeDocument(CurrentActor actor, @PathVariable String documentId, @RequestParam String agentKey) {
        actor.requireAdmin();
        agentKnowledgeDocumentService.deleteDocument(actor.tenantId(), agentKey, documentId);
    }

    @PostMapping("/rag-evaluations/{messageId}/judgments")
    public ResponseEntity<Void> judgeRagCandidate(CurrentActor actor, @PathVariable long messageId,
                                                   @RequestBody RagJudgmentRequest request) {
        boolean saved = agentConversationService.saveRagJudgment(actor.tenantId(), actor.userId(), request.agentKey(),
                messageId, request.candidateId(), request.judgment());
        return saved ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    public record RagJudgmentRequest(String agentKey, String candidateId, String judgment) { }
}
