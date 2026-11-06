package com.zwx.zwxagent.creative.story;

import com.zwx.zwxagent.creative.domain.CandidateStatus;
import com.zwx.zwxagent.creative.domain.CreativeConflictException;
import com.zwx.zwxagent.creative.domain.ShotStatus;
import com.zwx.zwxagent.creative.domain.VideoShotCandidateRecord;
import com.zwx.zwxagent.creative.domain.VideoShotRecord;
import com.zwx.zwxagent.creative.repository.CreativeAssetRepository;
import com.zwx.zwxagent.creative.repository.VideoShotCandidateRepository;
import com.zwx.zwxagent.creative.repository.VideoStoryContentRepository;
import com.zwx.zwxagent.creative.service.CreativeProjectService;
import com.zwx.zwxagent.creative.service.MediaJobService;
import com.zwx.zwxagent.creative.service.MediaModelResolver;
import com.zwx.zwxagent.creative.support.ImageStyleCatalog;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 镜头生产闭环（P2）：从镜头发起生成（候选预建 + prompt 冻结 + MediaJob 受理）→
 * 调度器回写产物（ShotCandidateResumeScheduler）→ 人工选片（乐观锁 + 单选约束）。
 * 生成成功不自动选定；上传已有视频作为候选是显式操作，不重新生成。
 */
@Service
public class ShotProductionService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ShotProductionService.class);

    private final StoryContentService contentService;
    private final VideoStoryContentRepository contentRepository;
    private final VideoShotCandidateRepository candidateRepository;
    private final CreativeAssetRepository assetRepository;
    private final MediaJobService mediaJobService;
    private final CreativeProjectService projectService;
    private final MediaModelResolver mediaModelResolver;
    private final com.zwx.zwxagent.creative.repository.MediaModelProfileRepository profileRepository;
    private final TransactionTemplate transactionTemplate;
    @org.springframework.beans.factory.annotation.Value("${app.pricing.usd-cny-rate:7.2}")
    private double usdCnyRate;

    public ShotProductionService(StoryContentService contentService,
                                 VideoStoryContentRepository contentRepository,
                                 VideoShotCandidateRepository candidateRepository,
                                 CreativeAssetRepository assetRepository,
                                 MediaJobService mediaJobService,
                                 CreativeProjectService projectService,
                                 MediaModelResolver mediaModelResolver,
                                 com.zwx.zwxagent.creative.repository.MediaModelProfileRepository profileRepository,
                                 TransactionTemplate transactionTemplate) {
        this.contentService = contentService;
        this.contentRepository = contentRepository;
        this.candidateRepository = candidateRepository;
        this.assetRepository = assetRepository;
        this.mediaJobService = mediaJobService;
        this.projectService = projectService;
        this.mediaModelResolver = mediaModelResolver;
        this.profileRepository = profileRepository;
        this.transactionTemplate = transactionTemplate;
    }

    public record GenerateRequest(Long expectedVersion, Integer seconds) {
    }

    public record SelectRequest(UUID candidateId, Long expectedVersion) {
    }

    /**
     * 候选视图：候选本体 + 关联任务状态 + 产物摘要（镜头检查器直接展示分辨率与时长）。
     * jobError 是给用户的可读原因，jobErrorRaw 保留供应商原文供诊断。
     */
    public record CandidateView(VideoShotCandidateRecord candidate, String jobStatus, String jobError,
                                String jobErrorRaw, String jobErrorHint,
                                VideoShotCandidateRepository.CandidateAsset asset) {
    }

    /** 从镜头发起一次生成：候选先建（冻结提交文本），任务幂等键绑定候选 ID。 */
    public CandidateView generate(CurrentActor actor, UUID projectId, UUID shotId, GenerateRequest request) {
        var shot = contentService.getShot(actor, projectId, shotId);
        assertVersion(shot, request.expectedVersion());
        var status = ShotStatus.valueOf(shot.status());
        if (!status.canGenerate()) {
            throw new CreativeConflictException("镜头当前状态为「" + status + "」，生成在途或已选定；先处理现有候选");
        }
        String prompt = shot.promptText() == null ? "" : shot.promptText().trim();
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("镜头缺少提交提示词；请先完善提示词或使用优化入口");
        }
        int seconds = request.seconds() != null && request.seconds() > 0
                ? Math.min(request.seconds(), 60) : Math.max(1, shot.durationTargetSeconds());

        // 状态先行占位（版本围栏）：并发第二次生成会在这里被挡住
        if (!contentRepository.updateShotStatus(shotId, shot.version(), ShotStatus.GENERATING.name())) {
            throw new CreativeConflictException("镜头状态已被其他操作改变，请刷新后重试");
        }
        try {
            return toView(submitGeneration(actor, projectId, shot, seconds));
        } catch (RuntimeException exception) {
            // 受理失败（未配置模型/参数拒绝等）：回滚占位并如实清理候选，不留孤儿
            contentRepository.transitionShotStatus(shotId, ShotStatus.GENERATING.name(),
                    ShotStatus.READY.name());
            throw exception;
        }
    }

    /**
     * 受理内核（P3 批次复用）：候选预建 + MediaJob 创建 + job 回填。
     * 调用方负责镜头状态占位与失败回滚；返回落库后的候选记录。
     */
    public VideoShotCandidateRecord submitGeneration(CurrentActor actor, UUID projectId,
                                                     VideoShotRecord shot, int seconds) {
        String prompt = shot.promptText() == null ? "" : shot.promptText().trim();
        // 画风贯通（REQ-STYLE）：镜头覆盖 > 项目默认；跟随原片对无首帧的镜头不注入空指令
        String projectStyle = "";
        try {
            projectStyle = String.valueOf(projectService.settingsOf(actor, projectId)
                    .getOrDefault("videoStyle", ""));
        } catch (RuntimeException ignored) {
            // 设定读取失败不阻断生成，只少风格注记
        }
        java.util.List<UUID> references = shot.referenceAssetIds() == null
                ? List.of() : shot.referenceAssetIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        String effectiveStyle = shot.styleCode() == null || shot.styleCode().isBlank()
                ? projectStyle : shot.styleCode();
        if ("PHOTO_MATCH".equalsIgnoreCase(effectiveStyle) && references.isEmpty()) {
            effectiveStyle = "PHOTO_MATCH".equalsIgnoreCase(projectStyle) ? "" : projectStyle;
        }
        String styledPrompt = ImageStyleCatalog.applyToPrompt(prompt, effectiveStyle);
        String operation = references.isEmpty()
                ? com.zwx.zwxagent.creative.domain.ParameterSchema.TEXT_TO_VIDEO
                : com.zwx.zwxagent.creative.domain.ParameterSchema.IMAGE_TO_VIDEO;
        // 时长按模型档位收敛（与预检同一规则）：避免提交供应商不支持的秒数被拒
        int effectiveSeconds = Math.max(1, seconds);
        try {
            var resolved = mediaModelResolver.resolve(actor.userId(), null, operation);
            int snapped = snapSeconds(effectiveSeconds, secondsSlots(resolved.parameterSchema()));
            if (snapped != effectiveSeconds) {
                log.info("镜头时长按模型档位调整：shot={} {}s → {}s", shot.id(), effectiveSeconds, snapped);
                effectiveSeconds = snapped;
            }
        } catch (RuntimeException ignored) {
            // 解析失败交给 MediaJobService 给出明确错误
        }
        var candidate = new VideoShotCandidateRecord(UUID.randomUUID(), projectId, actor.tenantId(),
                actor.userId(), shot.id(), null, null, "GENERATED", styledPrompt,
                CandidateStatus.GENERATING.name(), null, null);
        candidateRepository.insert(candidate);
        try {
            var created = mediaJobService.create(actor, "shot-" + shot.id() + "-" + candidate.id(), projectId,
                    new MediaJobService.CreateJobRequest(projectId, operation, null,
                            styledPrompt, styledPrompt, Map.of("seconds", String.valueOf(effectiveSeconds)),
                            references, null, null, false));
            candidateRepository.attachJob(candidate.id(), created.jobId());
        } catch (RuntimeException exception) {
            candidateRepository.delete(candidate.id());
            throw exception;
        }
        return candidateRepository.findById(actor.tenantId(), actor.userId(), candidate.id()).orElseThrow();
    }

    // ---------------------------------------------------------------- 生成预检（P1：纯校验，不创建任务、不调用模型）

    /** 单镜预检：requestedSeconds 与 effectiveSeconds 不同表示按模型档位自动调整（不阻塞）。 */
    public record PreflightItem(UUID shotId, int shotNumber, boolean ok, String reason,
                                String operation, int requestedSeconds, int effectiveSeconds,
                                boolean adjusted, int references, String estimatedCost) {
    }

    /** 整批预检：费用按模型单价 × 秒数估算；单价缺失时明确「费用未知」。 */
    public record PreflightResult(String modelName, String adapterKey, List<PreflightItem> items,
                                  int readyCount, int adjustedCount, String estimatedTotal, String costNote) {
    }

    /**
     * 生成预检（需求 §9.2-4）：逐镜给出可提交/阻塞原因、实际 operation（首帧决定）、秒数与任务数；
     * 费用没有可靠来源时如实返回「费用未知」，不显示 0。预检本身不调用媒体生成模型。
     */
    public PreflightResult preflight(CurrentActor actor, UUID projectId, List<UUID> shotIds) {
        var all = contentRepository.listShotsByProject(actor.tenantId(), actor.userId(), projectId)
                .stream().collect(java.util.stream.Collectors.toMap(VideoShotRecord::id, shot -> shot));
        String projectStyle = "";
        try {
            projectStyle = String.valueOf(projectService.settingsOf(actor, projectId)
                    .getOrDefault("videoStyle", ""));
        } catch (RuntimeException ignored) {
            // 预检阶段读不到设定不视为错误
        }
        String projectAdapter = null;
        String projectModel = null;
        java.util.List<Integer> slots = List.of();
        java.math.BigDecimal unitPrice = null;
        String unit = "";
        String currency = "USD";
        List<PreflightItem> items = new java.util.ArrayList<>();
        int ready = 0;
        int adjusted = 0;
        for (UUID shotId : shotIds == null ? List.<UUID>of() : shotIds.stream().distinct().toList()) {
            var shot = all.get(shotId);
            if (shot == null) {
                items.add(new PreflightItem(shotId, 0, false, "镜头不存在或无权访问", "text_to_video",
                        0, 0, false, 0, ""));
                continue;
            }
            java.util.List<UUID> references = shot.referenceAssetIds() == null
                    ? List.of() : shot.referenceAssetIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
            String operation = references.isEmpty()
                    ? com.zwx.zwxagent.creative.domain.ParameterSchema.TEXT_TO_VIDEO
                    : com.zwx.zwxagent.creative.domain.ParameterSchema.IMAGE_TO_VIDEO;
            int requested = Math.max(1, shot.durationTargetSeconds());
            int effective = requested;
            String reason = null;
            if (!com.zwx.zwxagent.creative.domain.ShotStatus.DRAFT.name().equals(shot.status())
                    && !com.zwx.zwxagent.creative.domain.ShotStatus.READY.name().equals(shot.status())) {
                reason = "镜头状态为 " + shot.status() + "，不能重复提交";
            }
            if (reason == null && (shot.promptText() == null || shot.promptText().isBlank())) {
                reason = "缺少提示词";
            }
            if (reason == null) {
                try {
                    assetReferenceCheck(actor, references);
                } catch (RuntimeException exception) {
                    reason = exception.getMessage();
                }
            }
            if (reason == null) {
                try {
                    var resolved = mediaModelResolver.resolve(actor.userId(), null, operation);
                    projectModel = resolved.modelName();
                    projectAdapter = resolved.adapterKey();
                    slots = secondsSlots(resolved.parameterSchema());
                    var profile = profileRepository.findByModelRowId(resolved.modelRowId()).orElse(null);
                    var price = profile == null ? null : profile.priceMetadata();
                    if (price != null) {
                        unit = String.valueOf(price.getOrDefault("unit", ""));
                        currency = String.valueOf(price.getOrDefault("currency", "USD"));
                        Object value = price.get("price");
                        if (value instanceof Number number) unitPrice = java.math.BigDecimal.valueOf(number.doubleValue());
                    }
                } catch (RuntimeException exception) {
                    reason = exception.getMessage();
                }
            }
            if (reason == null) {
                effective = snapSeconds(requested, slots);
                if (effective != requested) adjusted++;
                ready++;
            }
            String itemCost = estimatedCost(unitPrice, unit, effective);
            items.add(new PreflightItem(shotId, shot.shotNumber(), reason == null, reason,
                    operation, requested, effective, effective != requested, references.size(), itemCost));
        }
        String total = totalCost(items, unitPrice, unit, currency);
        String note = unitPrice == null
                ? "费用未知（该模型未配置单价，以供应商账单为准）"
                : ("按 " + currency + " " + unitPrice.stripTrailingZeros().toPlainString()
                        + ("second".equalsIgnoreCase(unit) ? "/秒" : "/次") + " 估算，实际以供应商账单为准");
        return new PreflightResult(projectModel, projectAdapter, items, ready, adjusted, total, note);
    }

    /** 模型支持的秒数档位（schema 的 seconds.allowedValues）；空 = 未声明，按原值提交。 */
    static java.util.List<Integer> secondsSlots(com.zwx.zwxagent.creative.domain.ParameterSchema schema) {
        if (schema == null || schema.parameters() == null) return List.of();
        return schema.parameters().stream()
                .filter(spec -> "seconds".equals(spec.name()))
                .flatMap(spec -> spec.allowedValues().stream())
                .map(value -> {
                    try { return Integer.valueOf(value.trim()); } catch (RuntimeException ignored) { return null; }
                })
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
    }

    /** 收敛到最近的合法档位（等距取较大值，保证内容完整）；无档位声明时原样返回。 */
    static int snapSeconds(int requested, java.util.List<Integer> slots) {
        if (slots == null || slots.isEmpty()) return requested;
        int best = slots.getFirst();
        for (int slot : slots) {
            int distance = Math.abs(slot - requested);
            int bestDistance = Math.abs(best - requested);
            if (distance < bestDistance || (distance == bestDistance && slot > best)) best = slot;
        }
        return best;
    }

    private static String estimatedCost(java.math.BigDecimal unitPrice, String unit, int effectiveSeconds) {
        if (unitPrice == null) return "";
        var value = "second".equalsIgnoreCase(unit)
                ? unitPrice.multiply(java.math.BigDecimal.valueOf(effectiveSeconds))
                : unitPrice;
        return "$" + value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String totalCost(List<PreflightItem> items, java.math.BigDecimal unitPrice, String unit, String currency) {
        if (unitPrice == null) return "";
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (PreflightItem item : items) {
            if (!item.ok()) continue;
            total = total.add("second".equalsIgnoreCase(unit)
                    ? unitPrice.multiply(java.math.BigDecimal.valueOf(item.effectiveSeconds()))
                    : unitPrice);
        }
        String usd = "$" + total.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        if (!"USD".equalsIgnoreCase(currency)) return usd;
        var cny = total.multiply(java.math.BigDecimal.valueOf(usdCnyRate)).setScale(0, java.math.RoundingMode.HALF_UP);
        return usd + "（≈ ¥" + cny.toPlainString() + "）";
    }

    private void assetReferenceCheck(CurrentActor actor, List<UUID> references) {
        for (UUID assetId : references) {
            var asset = assetRepository.findByIdForOwner(actor.tenantId(), actor.userId(), assetId)
                    .orElseThrow(() -> new IllegalArgumentException("首帧素材不存在或无权使用"));
            if (!"IMAGE".equals(asset.mediaType()) || !"READY".equals(asset.status().name())) {
                throw new IllegalArgumentException("首帧素材必须是已就绪的图片");
            }
        }
    }

    /** 上传已有视频作为候选（显式操作，不生成、不计费）。 */
    public CandidateView addUploadCandidate(CurrentActor actor, UUID projectId, UUID shotId, UUID assetId) {
        var shot = contentService.getShot(actor, projectId, shotId);
        var asset = assetRepository.findByIdForOwner(actor.tenantId(), actor.userId(), assetId)
                .orElseThrow(() -> new IllegalArgumentException("素材不存在或无权使用"));
        if (!"VIDEO".equals(asset.mediaType()) || !"READY".equals(asset.status().name())) {
            throw new IllegalArgumentException("只能选用本人已就绪的视频素材作为候选");
        }
        var candidate = new VideoShotCandidateRecord(UUID.randomUUID(), projectId, actor.tenantId(),
                actor.userId(), shotId, assetId, null, "UPLOAD", "",
                CandidateStatus.CANDIDATE.name(), null, null);
        candidateRepository.insert(candidate);
        contentRepository.transitionShotStatus(shotId, shot.status(), ShotStatus.PENDING_SELECTION.name());
        return toView(candidate);
    }

    /** 人工选片（乐观锁 + 单选约束在 DB 层兜底）；换选自动把旧候选退回待选。 */
    public CandidateView select(CurrentActor actor, UUID projectId, UUID shotId, SelectRequest request) {
        var shot = contentService.getShot(actor, projectId, shotId);
        assertVersion(shot, request.expectedVersion());
        var candidate = candidateRepository.findById(actor.tenantId(), actor.userId(), request.candidateId())
                .orElseThrow(() -> new IllegalArgumentException("候选不存在"));
        if (!candidate.shotId().equals(shotId)) {
            throw new CreativeConflictException("候选不属于该镜头");
        }
        if (!CandidateStatus.CANDIDATE.name().equals(candidate.status())) {
            throw new CreativeConflictException("候选当前不可选定（状态 " + candidate.status() + "）");
        }
        transactionTemplate.executeWithoutResult(status -> {
            candidateRepository.demoteSelected(shotId);
            if (!candidateRepository.updateStatus(candidate.id(), CandidateStatus.SELECTED.name())) {
                throw new CreativeConflictException("候选状态已变化，请刷新后重试");
            }
            contentRepository.transitionShotStatus(shotId, shot.status(), ShotStatus.SELECTED.name());
        });
        return toView(candidateRepository.findById(actor.tenantId(), actor.userId(), candidate.id()).orElseThrow());
    }

    /** 取消选定：镜头回到待选片，可重新选择或继续生成。 */
    public CandidateView unselect(CurrentActor actor, UUID projectId, UUID shotId, UUID candidateId) {
        contentService.getShot(actor, projectId, shotId);
        var candidate = candidateRepository.findById(actor.tenantId(), actor.userId(), candidateId)
                .orElseThrow(() -> new IllegalArgumentException("候选不存在"));
        if (!candidate.shotId().equals(shotId) || !CandidateStatus.SELECTED.name().equals(candidate.status())) {
            throw new CreativeConflictException("该候选不是当前选定结果");
        }
        transactionTemplate.executeWithoutResult(status -> {
            candidateRepository.updateStatus(candidateId, CandidateStatus.CANDIDATE.name());
            contentRepository.transitionShotStatus(shotId, ShotStatus.SELECTED.name(),
                    ShotStatus.PENDING_SELECTION.name());
        });
        return toView(candidateRepository.findById(actor.tenantId(), actor.userId(), candidateId).orElseThrow());
    }

    /** 删除候选（选定中的候选必须先取消选定）。 */
    public void deleteCandidate(CurrentActor actor, UUID projectId, UUID shotId, UUID candidateId) {
        contentService.getShot(actor, projectId, shotId);
        var candidate = candidateRepository.findById(actor.tenantId(), actor.userId(), candidateId)
                .orElseThrow(() -> new IllegalArgumentException("候选不存在"));
        if (!candidate.shotId().equals(shotId)) {
            throw new CreativeConflictException("候选不属于该镜头");
        }
        if (CandidateStatus.SELECTED.name().equals(candidate.status())) {
            throw new CreativeConflictException("已选定候选不可删除；请先取消选定");
        }
        candidateRepository.delete(candidateId);
    }

    public List<CandidateView> candidates(CurrentActor actor, UUID projectId, UUID shotId) {
        contentService.getShot(actor, projectId, shotId);
        return candidateRepository.listByShotWithJob(actor.tenantId(), actor.userId(), shotId).stream()
                .map(row -> toCandidateView(row))
                .toList();
    }

    private CandidateView toCandidateView(VideoShotCandidateRepository.CandidateWithJob row) {
        var humanized = com.zwx.zwxagent.creative.support.MediaErrorMessages.humanize(row.jobError());
        return new CandidateView(row.candidate(), row.jobStatus(), humanized.message(),
                humanized.raw(), humanized.hint(), row.asset());
    }

    // ---------------------------------------------------------------- 内部

    private CandidateView toView(VideoShotCandidateRecord candidate) {
        return new CandidateView(candidate, null, null, null, null, null);
    }

    private static void assertVersion(VideoShotRecord shot, Long expectedVersion) {
        if (expectedVersion != null && shot.version() != expectedVersion) {
            throw new CreativeConflictException("镜头已被其他页面修改（版本 " + shot.version()
                    + "），请刷新后重试");
        }
    }
}
