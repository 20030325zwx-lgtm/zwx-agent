package com.zwx.zwxagent.creative.service;

import com.zwx.zwxagent.creative.domain.CreativeConflictException;
import com.zwx.zwxagent.creative.domain.CreativeDraftRecord;
import com.zwx.zwxagent.creative.domain.CreativeProjectRecord;
import com.zwx.zwxagent.creative.domain.ProjectStatus;
import com.zwx.zwxagent.creative.repository.CreativeDraftRepository;
import com.zwx.zwxagent.creative.repository.CreativeProjectRepository;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 草稿（C03）：归属项目且项目 ACTIVE 才能新增（BR02）；
 * 草稿允许空提示词，非空时受平台 8000 字上限（BR05）；更新走 expectedVersion（BR18）。
 */
@Service
public class CreativeDraftService {

    public static final String OPERATION_TEXT_TO_IMAGE = "text_to_image";
    public static final int PROMPT_MAX = 8000;

    public record DraftView(UUID id, UUID projectId, String operation, String originalPrompt,
                            String effectivePrompt, Long modelRowId, long version,
                            Instant createdAt, Instant updatedAt) {
    }

    public record CreateDraftRequest(String operation, String originalPrompt, String effectivePrompt) {
    }

    public record UpdateDraftRequest(String operation, String originalPrompt, String effectivePrompt,
                                     Long expectedVersion) {
    }

    private final CreativeProjectRepository projectRepository;
    private final CreativeDraftRepository draftRepository;

    public CreativeDraftService(CreativeProjectRepository projectRepository,
                                CreativeDraftRepository draftRepository) {
        this.projectRepository = projectRepository;
        this.draftRepository = draftRepository;
    }

    public DraftView create(CurrentActor actor, UUID projectId, CreateDraftRequest request) {
        var project = lockProject(actor, projectId);
        if (project.status() != ProjectStatus.ACTIVE) {
            throw new CreativeConflictException("项目已归档，不能新增草稿");
        }
        String operation = normalizeOperation(request == null ? null : request.operation());
        var prompts = normalizePrompts(request);
        var record = new CreativeDraftRecord(
                UUID.randomUUID(), projectId, actor.tenantId(), actor.userId(), operation,
                prompts.originalPrompt(), prompts.effectivePrompt(), java.util.List.of(),
                null, java.util.Map.of(), 1, Instant.now(), Instant.now());
        draftRepository.insert(record);
        return toView(draftRepository.findByIdForOwner(actor.tenantId(), actor.userId(), record.id())
                .orElseThrow());
    }

    public DraftView get(CurrentActor actor, UUID draftId) {
        return draftRepository.findByIdForOwner(actor.tenantId(), actor.userId(), draftId)
                .map(CreativeDraftService::toView)
                .orElseThrow(() -> new IllegalArgumentException("草稿不存在"));
    }

    public java.util.List<DraftView> list(CurrentActor actor, UUID projectId, int limit) {
        return draftRepository.listByProject(actor.tenantId(), actor.userId(), projectId, limit)
                .stream().map(CreativeDraftService::toView).toList();
    }

    public DraftView update(CurrentActor actor, UUID draftId, UpdateDraftRequest request) {
        var existing = draftRepository.findByIdForOwner(actor.tenantId(), actor.userId(), draftId)
                .orElseThrow(() -> new IllegalArgumentException("草稿不存在"));
        String operation = normalizeOperation(request == null ? null : request.operation());
        var prompts = normalizePrompts(request);
        long expectedVersion = request == null || request.expectedVersion() == null
                ? existing.version() : request.expectedVersion();
        if (!draftRepository.updateContent(draftId, actor.tenantId(), actor.userId(), expectedVersion,
                operation, prompts.originalPrompt(), prompts.effectivePrompt(),
                existing.referenceAssetIds(), existing.modelRowId(), existing.parameters())) {
            throw new CreativeConflictException("草稿已被其他修改更新，请刷新后重试");
        }
        return get(actor, draftId);
    }

    private CreativeProjectRecord lockProject(CurrentActor actor, UUID projectId) {
        return projectRepository.lockByIdForOwner(actor.tenantId(), actor.userId(), projectId)
                .orElseThrow(() -> new IllegalArgumentException("创作项目不存在"));
    }

    private static String normalizeOperation(String operation) {
        String value = operation == null || operation.isBlank() ? OPERATION_TEXT_TO_IMAGE : operation.trim();
        if (!OPERATION_TEXT_TO_IMAGE.equals(value)) {
            throw new IllegalArgumentException("暂不支持的创作类型：" + value);
        }
        return value;
    }

    private record Prompts(String originalPrompt, String effectivePrompt) {
    }

    private static Prompts normalizePrompts(Object request) {
        String original = request instanceof CreateDraftRequest r ? r.originalPrompt()
                : request instanceof UpdateDraftRequest u ? u.originalPrompt() : null;
        String effective = request instanceof CreateDraftRequest r ? r.effectivePrompt()
                : request instanceof UpdateDraftRequest u ? u.effectivePrompt() : null;
        return new Prompts(normalizePrompt(original, "originalPrompt"),
                normalizePrompt(effective, "effectivePrompt"));
    }

    private static String normalizePrompt(String prompt, String field) {
        String value = prompt == null ? "" : prompt;
        if (value.length() > PROMPT_MAX) {
            throw new IllegalArgumentException(field + " 不能超过 " + PROMPT_MAX + " 个字符");
        }
        return value;
    }

    private static DraftView toView(CreativeDraftRecord record) {
        return new DraftView(record.id(), record.projectId(), record.operation(), record.originalPrompt(),
                record.effectivePrompt(), record.modelRowId(), record.version(),
                record.createdAt(), record.updatedAt());
    }
}
