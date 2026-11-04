package com.zwx.zwxagent.creative.story;

import com.zwx.zwxagent.creative.domain.ShotStatus;
import com.zwx.zwxagent.creative.domain.VideoBatchItemRecord;
import com.zwx.zwxagent.creative.domain.VideoBatchRecord;
import com.zwx.zwxagent.creative.domain.VideoShotRecord;
import com.zwx.zwxagent.creative.repository.VideoBatchRepository;
import com.zwx.zwxagent.creative.repository.VideoStoryContentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 批次提交调度器（P3）：对 RUNNING 批次按并发上限逐个提交待提交子项（复用
 * ShotProductionService.submitGeneration 的候选预建 + MediaJob 幂等受理）。
 * 提交失败：attempt 未达上限回待提交退避重试，达上限如实 FAILED；镜头状态回 READY。
 * 完成收敛：无待提交/在途子项时批次转 COMPLETED/STOPPED。
 */
@Component
public class BatchSubmitScheduler {

    private static final Logger log = LoggerFactory.getLogger(BatchSubmitScheduler.class);
    private static final int MAX_ATTEMPT = 3;

    private final VideoBatchRepository batchRepository;
    private final VideoStoryContentRepository contentRepository;
    private final ShotProductionService productionService;

    @org.springframework.beans.factory.annotation.Value("${app.story.batch.submit-enabled:true}")
    private boolean submitEnabled;

    public BatchSubmitScheduler(VideoBatchRepository batchRepository,
                                VideoStoryContentRepository contentRepository,
                                ShotProductionService productionService) {
        this.batchRepository = batchRepository;
        this.contentRepository = contentRepository;
        this.productionService = productionService;
    }

    @Scheduled(fixedDelayString = "${app.story.batch.submit-interval-ms:2000}")
    public void tick() {
        if (!submitEnabled) return;
        try {
            var batches = batchRepository.listRunningBatches(5);
            for (var batch : batches) {
                try {
                    submitAvailable(batch);
                } catch (RuntimeException exception) {
                    log.warn("批次提交调度失败：batch={}", batch.id(), exception);
                }
            }
        } catch (RuntimeException exception) {
            log.warn("批次提交扫描失败：{}", exception.getMessage());
        }
    }

    private void submitAvailable(VideoBatchRecord batch) {
        int inflight = batchRepository.countInflight(batch.id());
        int available = batch.concurrency() - inflight;
        if (available <= 0) return;
        List<VideoBatchItemRecord> pending = batchRepository.listPendingItems(batch.id(), available);
        for (var item : pending) {
            submitItem(batch, item);
        }
        batchRepository.finalizeBatchIfSettled(batch.id());
    }

    private void submitItem(VideoBatchRecord batch, VideoBatchItemRecord item) {
        var shot = contentRepository.findShot(item.tenantId(), item.userId(), item.shotId()).orElse(null);
        if (shot == null) {
            batchRepository.markFailed(item.id(), "镜头不存在或无权访问");
            return;
        }
        if (!ShotStatus.valueOf(shot.status()).canGenerate()) {
            // 镜头被并发操作占用/已选定：本轮跳过，下轮再看；超上限如实失败
            if (item.attempt() + 1 >= MAX_ATTEMPT) {
                batchRepository.markFailed(item.id(), "镜头状态长期不可生成（" + shot.status() + "）");
            }
            return;
        }
        if (!contentRepository.updateShotStatus(shot.id(), shot.version(), ShotStatus.GENERATING.name())) {
            return; // 版本冲突：下一轮重读再试
        }
        try {
            var candidate = productionService.submitGeneration(
                    actorOf(item), item.projectId(), shot, shot.durationTargetSeconds());
            if (!batchRepository.markSubmitted(item.id(), candidate.id(), candidate.jobId(),
                    item.attempt() + 1)) {
                log.warn("批次子项状态竞争：item={}", item.id());
            }
        } catch (RuntimeException exception) {
            contentRepository.transitionShotStatus(shot.id(), ShotStatus.GENERATING.name(),
                    ShotStatus.READY.name());
            String message = rootMessage(exception);
            if (item.attempt() + 1 >= MAX_ATTEMPT) {
                batchRepository.markFailed(item.id(), "提交失败：" + message);
            } else {
                // 未达上限：回待提交并记录 attempt（下一次 tick 退避重试）
                batchRepository.markFailed(item.id(), "提交失败（将重试）：" + message);
                batchRepository.resetItemForRetry(item.id(), item.attempt() + 1);
            }
        }
    }

    private static com.zwx.zwxagent.platform.security.CurrentActor actorOf(VideoBatchItemRecord item) {
        return new com.zwx.zwxagent.platform.security.CurrentActor(
                item.userId(), item.tenantId(), item.userId(),
                com.zwx.zwxagent.platform.security.Role.USER);
    }

    private static String rootMessage(RuntimeException exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
