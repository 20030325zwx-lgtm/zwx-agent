package com.zwx.zwxagent.creative.story;

import com.zwx.zwxagent.creative.domain.CandidateStatus;
import com.zwx.zwxagent.creative.domain.CreativeConflictException;
import com.zwx.zwxagent.creative.domain.ShotStatus;
import com.zwx.zwxagent.creative.domain.VideoShotCandidateRecord;
import com.zwx.zwxagent.creative.domain.VideoShotRecord;
import com.zwx.zwxagent.creative.repository.CreativeAssetRepository;
import com.zwx.zwxagent.creative.repository.VideoShotCandidateRepository;
import com.zwx.zwxagent.creative.repository.VideoStoryContentRepository;
import com.zwx.zwxagent.creative.service.MediaJobService;
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

    private final StoryContentService contentService;
    private final VideoStoryContentRepository contentRepository;
    private final VideoShotCandidateRepository candidateRepository;
    private final CreativeAssetRepository assetRepository;
    private final MediaJobService mediaJobService;
    private final TransactionTemplate transactionTemplate;

    public ShotProductionService(StoryContentService contentService,
                                 VideoStoryContentRepository contentRepository,
                                 VideoShotCandidateRepository candidateRepository,
                                 CreativeAssetRepository assetRepository,
                                 MediaJobService mediaJobService,
                                 TransactionTemplate transactionTemplate) {
        this.contentService = contentService;
        this.contentRepository = contentRepository;
        this.candidateRepository = candidateRepository;
        this.assetRepository = assetRepository;
        this.mediaJobService = mediaJobService;
        this.transactionTemplate = transactionTemplate;
    }

    public record GenerateRequest(Long expectedVersion, Integer seconds) {
    }

    public record SelectRequest(UUID candidateId, Long expectedVersion) {
    }

    /** 候选视图：候选本体 + 关联任务状态（轻量任务关联，镜头抽屉直接展示）。 */
    public record CandidateView(VideoShotCandidateRecord candidate, String jobStatus, String jobError) {
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
        var candidate = new VideoShotCandidateRecord(UUID.randomUUID(), projectId, actor.tenantId(),
                actor.userId(), shotId, null, null, "GENERATED", prompt,
                CandidateStatus.GENERATING.name(), null, null);
        candidateRepository.insert(candidate);
        try {
            var created = mediaJobService.create(actor, "shot-" + shotId + "-" + candidate.id(), projectId,
                    new MediaJobService.CreateJobRequest(projectId, "text_to_video", null,
                            prompt, prompt, Map.of("seconds", String.valueOf(seconds)),
                            List.of(), null, null, false));
            candidateRepository.attachJob(candidate.id(), created.jobId());
        } catch (RuntimeException exception) {
            // 受理失败（未配置模型/参数拒绝等）：回滚占位并如实清理候选，不留孤儿
            candidateRepository.delete(candidate.id());
            contentRepository.transitionShotStatus(shotId, ShotStatus.GENERATING.name(),
                    ShotStatus.READY.name());
            throw exception;
        }
        return toView(candidateRepository.findById(actor.tenantId(), actor.userId(), candidate.id()).orElseThrow());
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
                .map(row -> new CandidateView(row.candidate(), row.jobStatus(), row.jobError()))
                .toList();
    }

    // ---------------------------------------------------------------- 内部

    private CandidateView toView(VideoShotCandidateRecord candidate) {
        return new CandidateView(candidate, null, null);
    }

    private static void assertVersion(VideoShotRecord shot, Long expectedVersion) {
        if (expectedVersion != null && shot.version() != expectedVersion) {
            throw new CreativeConflictException("镜头已被其他页面修改（版本 " + shot.version()
                    + "），请刷新后重试");
        }
    }
}
