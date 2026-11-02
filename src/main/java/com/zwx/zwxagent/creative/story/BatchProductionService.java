package com.zwx.zwxagent.creative.story;

import com.zwx.zwxagent.creative.domain.CreativeConflictException;
import com.zwx.zwxagent.creative.domain.ShotStatus;
import com.zwx.zwxagent.creative.domain.VideoBatchItemRecord;
import com.zwx.zwxagent.creative.domain.VideoBatchRecord;
import com.zwx.zwxagent.creative.domain.VideoShotRecord;
import com.zwx.zwxagent.creative.repository.VideoBatchRepository;
import com.zwx.zwxagent.creative.repository.VideoStoryContentRepository;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 批次生产（P3）：批次是业务聚合，不是新的执行引擎——子项提交复用
 * {@link ShotProductionService#submitGeneration}（候选预建 + MediaJob 受理 + 幂等），
 * 回写复用 ShotCandidateResumeScheduler；本服务只管理批次范围、并发提交与停止/重试语义。
 * 停止 = 停止未提交子项（SKIPPED），在途任务按供应商真实能力处理（不自动取消上游）。
 */
@Service
public class BatchProductionService {

    public static final int MAX_BATCH_ITEMS = 12;

    private final StoryContentService contentService;
    private final ShotProductionService productionService;
    private final VideoBatchRepository batchRepository;
    private final VideoStoryContentRepository contentRepository;
    private final int defaultConcurrency;

    public BatchProductionService(StoryContentService contentService,
                                  ShotProductionService productionService,
                                  VideoBatchRepository batchRepository,
                                  VideoStoryContentRepository contentRepository,
                                  @Value("${app.story.batch.concurrency:2}") int defaultConcurrency) {
        this.contentService = contentService;
        this.productionService = productionService;
        this.batchRepository = batchRepository;
        this.contentRepository = contentRepository;
        this.defaultConcurrency = Math.max(1, defaultConcurrency);
    }

    public record CreateBatchRequest(List<UUID> shotIds, String scopeLabel) {
    }

    /** 创建批次：校验镜头可生成（READY/DRAFT 且有提示词），去重并限制批次规模。 */
    public VideoBatchRecord createBatch(CurrentActor actor, UUID projectId, CreateBatchRequest request) {
        contentService.checkProject(actor, projectId);
        if (request == null || request.shotIds() == null || request.shotIds().isEmpty()) {
            throw new IllegalArgumentException("请选择要批量生成的镜头");
        }
        Set<UUID> shotIds = new LinkedHashSet<>(request.shotIds());
        if (shotIds.size() > MAX_BATCH_ITEMS) {
            throw new IllegalArgumentException("单批最多 " + MAX_BATCH_ITEMS + " 个镜头（当前 " + shotIds.size() + "）");
        }
        for (UUID shotId : shotIds) {
            var shot = contentService.getShot(actor, projectId, shotId);
            if (!ShotStatus.valueOf(shot.status()).canGenerate()) {
                throw new CreativeConflictException("镜头 " + shot.shotNumber() + " 当前不可生成（"
                        + shot.status() + "）");
            }
            if (shot.promptText() == null || shot.promptText().isBlank()) {
                throw new CreativeConflictException("镜头 " + shot.shotNumber() + " 缺少提交提示词");
            }
        }
        var batch = new VideoBatchRecord(UUID.randomUUID(), projectId, actor.tenantId(), actor.userId(),
                request.scopeLabel() == null ? "" : request.scopeLabel().trim(),
                "RUNNING", defaultConcurrency, null, null);
        batchRepository.insertBatch(batch);
        for (UUID shotId : shotIds) {
            batchRepository.insertItem(new VideoBatchItemRecord(UUID.randomUUID(), projectId,
                    actor.tenantId(), actor.userId(), batch.id(), shotId,
                    "PENDING_SUBMIT", null, null, 0, null, null, null));
        }
        return batch;
    }

    /** 停止批次：停止未提交子项；在途任务继续，全部收敛后批次转 STOPPED/COMPLETED。 */
    public VideoBatchRecord stop(CurrentActor actor, UUID projectId, UUID batchId) {
        var batch = requireBatch(actor, projectId, batchId);
        if ("RUNNING".equals(batch.status())) {
            batchRepository.updateBatchStatus(batchId, "STOPPING");
            batchRepository.skipPending(batchId);
        }
        batchRepository.finalizeBatchIfSettled(batchId);
        return batchRepository.findBatch(actor.tenantId(), actor.userId(), batchId).orElseThrow();
    }

    /** 重试失败项：FAILED 回待提交；已停止/已完成的批次重新进入 RUNNING。 */
    public VideoBatchRecord retryFailed(CurrentActor actor, UUID projectId, UUID batchId) {
        var batch = requireBatch(actor, projectId, batchId);
        int reset = batchRepository.resetFailedForRetry(batchId);
        if (reset == 0) {
            throw new CreativeConflictException("没有可重试的失败项");
        }
        if (!batch.acceptingSubmissions()) {
            batchRepository.updateBatchStatus(batchId, "RUNNING");
        }
        return batchRepository.findBatch(actor.tenantId(), actor.userId(), batchId).orElseThrow();
    }

    /** 批次视图：批次 + 子项（含镜头定位标签与候选资产），供进度面板。 */
    public BatchView view(CurrentActor actor, UUID projectId, UUID batchId) {
        var batch = requireBatch(actor, projectId, batchId);
        var items = batchRepository.listItems(actor.tenantId(), actor.userId(), batchId);
        Map<UUID, VideoShotRecord> shots = contentRepository.listShotsByProject(
                actor.tenantId(), actor.userId(), projectId).stream()
                .collect(Collectors.toMap(VideoShotRecord::id, shot -> shot));
        List<ItemView> views = items.stream().map(item -> {
            var shot = shots.get(item.shotId());
            return new ItemView(item, shot == null ? "未知镜头" : shotLabel(shot),
                    shot == null ? 4 : shot.durationTargetSeconds());
        }).toList();
        return new BatchView(batch, views, aggregate(views));
    }

    public List<VideoBatchRecord> list(CurrentActor actor, UUID projectId) {
        return batchRepository.listBatches(actor.tenantId(), actor.userId(), projectId, 20);
    }

    public record ItemView(VideoBatchItemRecord item, String shotLabel, int seconds) {
    }

    public record BatchView(VideoBatchRecord batch, List<ItemView> items, Progress progress) {
    }

    /** 进度聚合（纯函数，单测覆盖）：待提交/在途/成功/失败/跳过分计数，完成率不含排除项。 */
    public static Progress aggregate(List<ItemView> items) {
        int pending = 0, inflight = 0, done = 0, failed = 0, skipped = 0;
        for (ItemView view : items) {
            switch (view.item().status()) {
                case "PENDING_SUBMIT" -> pending++;
                case "SUBMITTED" -> inflight++;
                case "DONE" -> done++;
                case "FAILED" -> failed++;
                case "SKIPPED" -> skipped++;
                default -> { /* 未知状态不计入 */ }
            }
        }
        int actionable = items.size() - skipped;
        int percent = actionable <= 0 ? 0 : Math.round(done * 100.0f / actionable);
        return new Progress(items.size(), pending, inflight, done, failed, skipped, percent);
    }

    public record Progress(int total, int pending, int inflight, int done, int failed, int skipped,
                           int donePercent) {
    }

    static String shotLabel(VideoShotRecord shot) {
        return "镜头 " + String.format("%02d", shot.shotNumber());
    }

    private VideoBatchRecord requireBatch(CurrentActor actor, UUID projectId, UUID batchId) {
        var batch = batchRepository.findBatch(actor.tenantId(), actor.userId(), batchId)
                .orElseThrow(() -> new IllegalArgumentException("批次不存在"));
        if (!batch.projectId().equals(projectId)) {
            throw new CreativeConflictException("批次不属于该创作项目");
        }
        return batch;
    }
}
