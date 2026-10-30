package com.zwx.zwxagent.creative.service;

import com.zwx.zwxagent.creative.domain.CreativeConflictException;
import com.zwx.zwxagent.creative.domain.CreativeQuotaException;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.creative.domain.CreativeException;
import com.zwx.zwxagent.creative.domain.InputSnapshot;
import com.zwx.zwxagent.creative.domain.JobSourceType;
import com.zwx.zwxagent.creative.domain.MediaJobRecord;
import com.zwx.zwxagent.creative.domain.MediaJobStatus;
import com.zwx.zwxagent.creative.domain.ModelSnapshot;
import com.zwx.zwxagent.creative.domain.ParameterSchema;
import com.zwx.zwxagent.creative.domain.ProjectStatus;
import com.zwx.zwxagent.creative.domain.ResolvedMediaModel;
import com.zwx.zwxagent.creative.repository.CreativeProjectRepository;
import com.zwx.zwxagent.creative.repository.MediaJobEventRepository;
import com.zwx.zwxagent.creative.repository.MediaJobRepository;
import com.zwx.zwxagent.creative.repository.MediaProviderQuotaRepository;
import com.zwx.zwxagent.creative.support.CreativeJson;
import com.zwx.zwxagent.creative.service.CreativeDraftService;
import com.zwx.zwxagent.platform.settings.ModelCatalogService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 媒体任务受理（C04）：
 * 幂等键为唯一防线——预检在锁项目行的事务内进行；插入冲突在事务外回读（PG 事务失败后不可继续）。
 * 受理期间不调用任何生成网络接口（A09）；成功即冻结输入与模型快照（BR19）。
 */
@Service
public class MediaJobService {

    public static final int DEFAULT_USER_MAX_QUEUED = 20;
    private static final String EVENT_JOB_CREATED = "JOB_CREATED";

    public record CreateJobRequest(UUID projectId, String operation, Long modelRowId,
                                   String originalPrompt, String effectivePrompt,
                                   Map<String, Object> parameters, java.util.List<UUID> referenceAssetIds, UUID draftId,
                                   UUID parentJobId, Boolean acknowledgePossibleDuplicateCharge) {
    }

    public record CreateJobResult(UUID jobId, String status, boolean reused, long version) {
    }

    private final MediaJobRepository jobRepository;
    private final MediaJobEventRepository eventRepository;
    private final CreativeProjectRepository projectRepository;
    private final MediaProviderQuotaRepository quotaRepository;
    private final MediaModelResolver modelResolver;
    private final TransactionTemplate transactionTemplate;

    public MediaJobService(MediaJobRepository jobRepository, MediaJobEventRepository eventRepository,
                           CreativeProjectRepository projectRepository,
                           MediaProviderQuotaRepository quotaRepository,
                           MediaModelResolver modelResolver, TransactionTemplate transactionTemplate) {
        this.jobRepository = jobRepository;
        this.eventRepository = eventRepository;
        this.projectRepository = projectRepository;
        this.quotaRepository = quotaRepository;
        this.modelResolver = modelResolver;
        this.transactionTemplate = transactionTemplate;
    }

    /** 归属内读取任务详情。 */
    public MediaJobRecord getForOwner(CurrentActor actor, UUID jobId) {
        return jobRepository.findByIdForOwner(actor.tenantId(), actor.userId(), jobId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
    }

    /**
     * 受理入口：结构校验 → 幂等预检（锁项目行）→ 解析模型与参数校验 → 受理事务（配额+插入+事件）。
     * 返回 new=202、reused=200，由控制器决定状态码。
     */
    public CreateJobResult create(CurrentActor actor, String idempotencyKey, UUID projectId,
                                  CreateJobRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key 必填（1–128 字符）");
        }
        String operation = normalizeOperation(request == null ? null : request.operation());
        String effectivePrompt = request == null ? null : request.effectivePrompt();
        if (effectivePrompt == null || effectivePrompt.isBlank()) {
            throw new IllegalArgumentException("缺少生成内容（effectivePrompt）");
        }
        var referenceAssetIds = request == null || request.referenceAssetIds() == null
                ? java.util.List.<UUID>of() : request.referenceAssetIds();
        // BR08：text_to_image 不接受参考图（P1）
        if (!referenceAssetIds.isEmpty()) {
            throw CreativeException.unsupportedParameter("text_to_image 不支持参考图（referenceAssetIds）");
        }
        validateParent(actor, request);

        String hash = requestHash(operation, request.originalPrompt(), effectivePrompt,
                request.modelRowId(), request.parameters());

        // 幂等预检（短事务，锁项目行）：同键复用 / 改正文 409
        var existing = transactionTemplate.execute(status -> {
            var project = projectRepository.lockByIdForOwner(actor.tenantId(), actor.userId(), projectId)
                    .orElseThrow(() -> new IllegalArgumentException("创作项目不存在"));
            if (project.status() != ProjectStatus.ACTIVE) {
                throw new CreativeConflictException("项目已归档，不能发起新任务");
            }
            return jobRepository.findByIdempotencyKey(actor.tenantId(), actor.userId(), idempotencyKey)
                    .map(job -> {
                        if (!job.requestHash().equals(hash)) {
                            throw new CreativeConflictException("相同幂等键对应不同内容，请更换 Idempotency-Key 后重试");
                        }
                        return job;
                    });
        });
        if (existing.isPresent()) return toResult(existing.get(), true);

        // 解析模型（BR03：仅 image_generation，不回退 chat）与参数校验
        ResolvedMediaModel model = modelResolver.resolve(actor.userId(), request.modelRowId());
        var parameters = request.parameters() == null ? Map.<String, Object>of() : request.parameters();
        var violations = model.parameterSchema().validate(parameters);
        if (!violations.isEmpty()) {
            throw CreativeException.unsupportedParameter(String.join("；", violations));
        }
        if (effectivePrompt.length() > ParameterSchema.MODEL_PROMPT_MAX) {
            throw CreativeException.unsupportedParameter("提示词超过模型上限 " + ParameterSchema.MODEL_PROMPT_MAX + " 个字符");
        }
        InputSnapshot input = new InputSnapshot(operation, effectivePrompt, parameters, referenceAssetIds);
        ModelSnapshot modelSnapshot = new ModelSnapshot(model.providerId(), model.modelRowId(),
                "DashScope", model.protocol(), model.baseUrl(), model.modelName(), model.adapterKey(),
                java.util.List.of(CreativeDraftService.OPERATION_TEXT_TO_IMAGE), model.profileRevision());

        try {
            var job = transactionTemplate.execute(status ->
                    accept(actor, projectId, idempotencyKey, hash, input, modelSnapshot, request));
            return toResult(job, false);
        } catch (DuplicateKeyException exception) {
            // 并发下另一事务先插入：回读原记录（A07）
            var job = jobRepository.findByIdempotencyKey(actor.tenantId(), actor.userId(), idempotencyKey)
                    .orElseThrow(() -> exception);
            return toResult(job, true);
        }
    }

    /** 受理事务：锁项目校验 ACTIVE → 排队配额 → 插入 QUEUED 与初始事件（同事务）。 */
    private MediaJobRecord accept(CurrentActor actor, UUID projectId, String idempotencyKey, String hash,
                                  InputSnapshot input, ModelSnapshot modelSnapshot, CreateJobRequest request) {
        var project = projectRepository.lockByIdForOwner(actor.tenantId(), actor.userId(), projectId)
                .orElseThrow(() -> new IllegalArgumentException("创作项目不存在"));
        if (project.status() != ProjectStatus.ACTIVE) {
            throw new CreativeConflictException("项目已归档，不能发起新任务");
        }
        int queued = jobRepository.countByOwnerAndStatus(actor.tenantId(), actor.userId(), MediaJobStatus.QUEUED);
        int maxQueued = maxQueuedFor(actor.userId());
        if (queued >= maxQueued) {
            throw new CreativeQuotaException("排队任务已达上限（" + maxQueued + "），请等待已有任务完成");
        }
        var job = new MediaJobRecord(UUID.randomUUID(), projectId, actor.tenantId(), actor.userId(),
                JobSourceType.UI, null, null, request == null ? null : request.parentJobId(),
                input.operation(), MediaJobStatus.QUEUED, idempotencyKey, hash, input, modelSnapshot,
                modelSnapshot.providerId(), null, null, 1, null, null, 0, false,
                Instant.now(), null, false, null, null, null,
                null, null, null, Instant.now(), Instant.now(), null);
        jobRepository.insert(job);
        eventRepository.append(job.id(), job.tenantId(), job.userId(), EVENT_JOB_CREATED,
                CreativeJson.toJson(java.util.Map.of("operation", input.operation(),
                        "status", MediaJobStatus.QUEUED.name())));
        return job;
    }

    private int maxQueuedFor(String userId) {
        return quotaRepository.findMaxQueued("USER", userId).orElse(DEFAULT_USER_MAX_QUEUED);
    }

    private void validateParent(CurrentActor actor, CreateJobRequest request) {
        if (request == null || request.parentJobId() == null) return;
        var parent = jobRepository.findByIdForOwner(actor.tenantId(), actor.userId(), request.parentJobId())
                .orElseThrow(() -> new IllegalArgumentException("父任务不存在"));
        boolean uncertain = parent.status() == MediaJobStatus.RECONCILING
                || parent.status() == MediaJobStatus.SUBMITTING;
        if (uncertain && !Boolean.TRUE.equals(request.acknowledgePossibleDuplicateCharge())) {
            throw new CreativeConflictException(
                    "父任务结果尚未确定，重复生成可能重复计费；请确认后再试（acknowledgePossibleDuplicateCharge）");
        }
    }

    private static CreateJobResult toResult(MediaJobRecord job, boolean reused) {
        return new CreateJobResult(job.id(), job.status().name(), reused, job.version());
    }

    private static String normalizeOperation(String operation) {
        String value = operation == null || operation.isBlank()
                ? CreativeDraftService.OPERATION_TEXT_TO_IMAGE : operation.trim();
        if (!CreativeDraftService.OPERATION_TEXT_TO_IMAGE.equals(value)) {
            throw new IllegalArgumentException("暂不支持的创作类型：" + value);
        }
        return value;
    }

    /** 稳定字段排序的请求哈希；不含时间、requestId 与短期 URL。 */
    static String requestHash(String operation, String originalPrompt, String effectivePrompt,
                              Long modelRowId, Map<String, Object> parameters) {
        var sorted = new TreeMap<>(parameters == null ? Map.<String, Object>of() : parameters);
        var canonical = new java.util.LinkedHashMap<String, Object>();
        canonical.put("operation", operation);
        canonical.put("originalPrompt", originalPrompt == null ? "" : originalPrompt);
        canonical.put("effectivePrompt", effectivePrompt);
        canonical.put("parameters", sorted);
        canonical.put("modelRowId", modelRowId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(CreativeJson.toJson(canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("请求哈希计算失败", exception);
        }
    }
}
