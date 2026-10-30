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

    @org.springframework.beans.factory.annotation.Value("${creative.submissions-enabled:true}")
    private boolean submissionsEnabled;

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

    /** 归属内按创建时间倒序分页；游标编码为 createdAtEpochMillis:id。 */
    public List<MediaJobRecord> list(CurrentActor actor, java.util.UUID projectId, String cursor, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        Instant cursorCreatedAt = null;
        UUID cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = cursor.split(":", 2);
            if (parts.length != 2) throw new IllegalArgumentException("无效的分页游标");
            try {
                cursorCreatedAt = Instant.ofEpochMilli(Long.parseLong(parts[0]));
                cursorId = UUID.fromString(parts[1]);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("无效的分页游标");
            }
        }
        return jobRepository.listByOwner(actor.tenantId(), actor.userId(), projectId,
                cursorCreatedAt, cursorId, size);
    }

    public static String cursorOf(MediaJobRecord job) {
        return job.createdAt().toEpochMilli() + ":" + job.id();
    }

    /** 前端可用动作必须来自后端判定，避免界面出现无效按钮（A29）。 */
    public static List<String> allowedActions(MediaJobStatus status) {
        return switch (status) {
            case QUEUED, RUNNING -> List.of("cancel");
            case INGEST_FAILED -> List.of("resumeIngest");
            case FAILED, CANCELLED -> List.of("retry");
            case RECONCILING -> List.of("retryWithRisk");
            case SUCCEEDED -> List.of("download", "recreate");
            default -> List.of();
        };
    }

    /**
     * 取消（A22）：QUEUED 直接取消且不产生任何上游调用；RUNNING 进入 CANCEL_REQUESTED，
     * 由调度器按供应商能力处理（不支持时继续跟踪结果，不假报 CANCELLED）；
     * 已生成结果或正在提交的任务拒绝取消。
     */
    public MediaJobRecord cancel(CurrentActor actor, UUID jobId) {
        return transactionTemplate.execute(status -> {
            var job = jobRepository.lockById(jobId).orElse(null);
            if (job == null || !job.tenantId().equals(actor.tenantId())
                    || !job.userId().equals(actor.userId())) {
                throw new IllegalArgumentException("任务不存在");
            }
            switch (job.status()) {
                case QUEUED -> {
                    if (jobRepository.updateStatusFenced(job.id(), job.version(), job.leaseEpoch(),
                            MediaJobStatus.CANCELLED, null, Instant.now())) {
                        eventRepository.append(job.id(), job.tenantId(), job.userId(), "CANCELLED",
                                CreativeJson.toJson(Map.of("reason", "user_requested")));
                    }
                }
                case RUNNING -> {
                    if (jobRepository.updateStatusFenced(job.id(), job.version(), job.leaseEpoch(),
                            MediaJobStatus.CANCEL_REQUESTED, Instant.now(), null)) {
                        eventRepository.append(job.id(), job.tenantId(), job.userId(), "CANCEL_REQUESTED",
                                CreativeJson.toJson(Map.of("operation", job.operation())));
                    }
                }
                case SUBMITTING -> throw new CreativeConflictException("任务正在提交，暂不能取消，请稍后重试");
                case FETCHING, INGEST_FAILED -> throw new CreativeConflictException(
                        "图片已生成，正在入库，不能取消");
                default -> { /* 终态幂等返回 */ }
            }
            return jobRepository.findById(job.id()).orElse(job);
        });
    }

    /** 入库恢复（A19/A20）：只重新下载与入库，不重新提交生成。 */
    public MediaJobRecord resumeIngest(CurrentActor actor, UUID jobId) {
        return transactionTemplate.execute(status -> {
            var job = jobRepository.lockById(jobId).orElse(null);
            if (job == null || !job.tenantId().equals(actor.tenantId())
                    || !job.userId().equals(actor.userId())) {
                throw new IllegalArgumentException("任务不存在");
            }
            if (job.status() != MediaJobStatus.INGEST_FAILED) {
                throw new CreativeConflictException("只有入库失败的任务可以恢复入库");
            }
            if (!jobRepository.updateStatusFenced(job.id(), job.version(), job.leaseEpoch(),
                    MediaJobStatus.FETCHING, Instant.now(), null)) {
                throw new CreativeConflictException("任务状态已变化，请刷新后重试");
            }
            eventRepository.append(job.id(), job.tenantId(), job.userId(), "FETCHING",
                    CreativeJson.toJson(Map.of("reason", "manual_ingest_retry")));
            return jobRepository.findById(job.id()).orElse(job);
        });
    }

    /**
     * 普通重试（A23）：仅 FAILED/CANCELLED 可建新 job 并关联 parent；
     * RECONCILING 结果不确定，必须显式确认可能重复计费；INGEST_FAILED 应走恢复入库。
     */
    public CreateJobResult retry(CurrentActor actor, UUID jobId, String idempotencyKey,
                                 boolean acknowledgePossibleDuplicateCharge) {
        var parent = getForOwner(actor, jobId);
        if (parent.status() == MediaJobStatus.INGEST_FAILED) {
            throw new CreativeConflictException("请使用恢复入库，不要重新生成");
        }
        if (parent.status() == MediaJobStatus.RECONCILING
                && !acknowledgePossibleDuplicateCharge) {
            throw new CreativeConflictException("原任务结果尚未确定，重试可能重复计费，请确认后再试");
        }
        if (parent.status() != MediaJobStatus.FAILED && parent.status() != MediaJobStatus.CANCELLED
                && parent.status() != MediaJobStatus.RECONCILING) {
            throw new CreativeConflictException("当前任务状态不支持重试");
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? "retry-" + jobId + "-" + UUID.randomUUID() : idempotencyKey;
        var input = parent.inputSnapshot();
        return create(actor, key, parent.projectId(), new CreateJobRequest(
                parent.projectId(), input.operation(), parent.modelSnapshot().modelRowId(),
                input.prompt(), input.prompt(), input.parameters(), List.of(),
                null, parent.id(), acknowledgePossibleDuplicateCharge));
    }

    /** 事件游标读取（A24）：只返回 seq 之后已提交的事件，调用方按 seq 去重。 */
    public List<com.zwx.zwxagent.creative.domain.MediaJobEventRecord> events(
            CurrentActor actor, UUID jobId, long afterSeq, int limit) {
        return eventPage(actor, jobId, afterSeq, limit).events();
    }

    /**
     * 事件分页：游标越界（大于当前最大 seq）时返回 reset + 最新快照，客户端丢弃本地事件重新同步；
     * 负游标直接拒绝。事件不删除，因此不存在"过期"缺口，但客户端可能持有其他任务的游标。
     */
    public EventPage eventPage(CurrentActor actor, UUID jobId, long afterSeq, int limit) {
        MediaJobRecord job = getForOwner(actor, jobId);
        if (afterSeq < 0) {
            throw new IllegalArgumentException("事件游标不能为负数");
        }
        long maxSeq = eventRepository.maxSeq(jobId);
        if (afterSeq > maxSeq) {
            return new EventPage(List.of(), maxSeq, true, job);
        }
        List<com.zwx.zwxagent.creative.domain.MediaJobEventRecord> events =
                eventRepository.listAfter(jobId, afterSeq, Math.max(1, Math.min(limit, 500)));
        long nextSeq = events.isEmpty() ? maxSeq : events.get(events.size() - 1).seq();
        return new EventPage(events, nextSeq, false, null);
    }

    public record EventPage(List<com.zwx.zwxagent.creative.domain.MediaJobEventRecord> events,
                            long nextSeq, boolean reset, MediaJobRecord snapshot) {
    }

    /**
     * 受理入口：结构校验 → 幂等预检（锁项目行）→ 解析模型与参数校验 → 受理事务（配额+插入+事件）。
     * 返回 new=202、reused=200，由控制器决定状态码。
     */
    public CreateJobResult create(CurrentActor actor, String idempotencyKey, UUID projectId,
                                  CreateJobRequest request) {
        return create(actor, idempotencyKey, projectId, request, JobSourceType.UI, null, null);
    }

    /**
     * 可信服务入口创建任务：来源由服务端注入（HTTP DTO 不接受该字段）。
     * agent 传 runId，工作流传 runId+nodeKey，用于费用归属与恢复追踪。
     */
    public CreateJobResult create(CurrentActor actor, String idempotencyKey, UUID projectId,
                                  CreateJobRequest request, JobSourceType sourceType,
                                  String sourceRunId, String sourceNodeId) {
        if (!submissionsEnabled) {
            throw CreativeException.submissionsDisabled();
        }
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
            if (project.kind() != com.zwx.zwxagent.creative.domain.CreativeKind.IMAGE) {
                throw CreativeException.unsupportedParameter("当前仅支持图片创作项目的生成任务");
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
                    accept(actor, projectId, idempotencyKey, hash, input, modelSnapshot, request,
                            sourceType, sourceRunId, sourceNodeId));
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
                                  InputSnapshot input, ModelSnapshot modelSnapshot, CreateJobRequest request,
                                  JobSourceType sourceType, String sourceRunId, String sourceNodeId) {
        var project = projectRepository.lockByIdForOwner(actor.tenantId(), actor.userId(), projectId)
                .orElseThrow(() -> new IllegalArgumentException("创作项目不存在"));
        if (project.status() != ProjectStatus.ACTIVE) {
            throw new CreativeConflictException("项目已归档，不能发起新任务");
        }
        if (project.kind() != com.zwx.zwxagent.creative.domain.CreativeKind.IMAGE) {
            throw CreativeException.unsupportedParameter("当前仅支持图片创作项目的生成任务");
        }
        int queued = jobRepository.countByOwnerAndStatus(actor.tenantId(), actor.userId(), MediaJobStatus.QUEUED);
        int maxQueued = maxQueuedFor(actor.userId());
        if (queued >= maxQueued) {
            throw new CreativeQuotaException("排队任务已达上限（" + maxQueued + "），请等待已有任务完成");
        }
        var job = new MediaJobRecord(UUID.randomUUID(), projectId, actor.tenantId(), actor.userId(),
                sourceType == null ? JobSourceType.UI : sourceType, sourceRunId, sourceNodeId,
                request == null ? null : request.parentJobId(),
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
