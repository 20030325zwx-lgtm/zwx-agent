package com.zwx.zwxagent.creative.story;

import com.zwx.zwxagent.creative.repository.VideoShotCandidateRepository;
import com.zwx.zwxagent.creative.repository.VideoStoryContentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 镜头候选回写调度器（P2）：扫描「GENERATING 且其 MediaJob 已终态」的候选（R02 模式：只认终态，防队首阻塞）。
 * SUCCEEDED → 首个 DONE 产物回写候选（CANDIDATE 待选片），镜头 → PENDING_SELECTION；
 * FAILED/CANCELLED → 候选 FAILED（如实保留），镜头 → READY。孤儿（无任务 ID）在观察窗口后清理由人工处理，
 * 不自动伪造失败。
 */
@Component
public class ShotCandidateResumeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ShotCandidateResumeScheduler.class);

    private final VideoShotCandidateRepository candidateRepository;
    private final VideoStoryContentRepository contentRepository;
    private final boolean enabled;

    public ShotCandidateResumeScheduler(VideoShotCandidateRepository candidateRepository,
                                        VideoStoryContentRepository contentRepository,
                                        @Value("${app.story.candidate-resume.enabled:true}") boolean enabled) {
        this.candidateRepository = candidateRepository;
        this.contentRepository = contentRepository;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.story.candidate-resume.interval-ms:5000}")
    public void tick() {
        if (!enabled) return;
        try {
            var rows = candidateRepository.listTerminalGenerating(10);
            for (var row : rows) {
                try {
                    handle(row);
                } catch (RuntimeException exception) {
                    log.warn("镜头候选回写失败：candidate={}", row.id(), exception);
                }
            }
        } catch (RuntimeException exception) {
            log.warn("镜头候选回写扫描失败：{}", exception.getMessage());
        }
    }

    private void handle(VideoShotCandidateRepository.TerminalCandidateRow row) {
        switch (row.jobStatus()) {
            case "SUCCEEDED" -> {
                var assetId = candidateRepository.firstDoneAsset(row.jobId());
                if (assetId.isEmpty()) {
                    // 任务成功但没有可入库产物：如实标失败（同媒体节点语义）
                    candidateRepository.markFailed(row.id());
                    contentRepository.transitionShotStatus(row.shotId(), "GENERATING", "READY");
                    return;
                }
                if (candidateRepository.attachAsset(row.id(), assetId.get())) {
                    contentRepository.transitionShotStatus(row.shotId(), "GENERATING", "PENDING_SELECTION");
                }
            }
            case "FAILED", "CANCELLED" -> {
                candidateRepository.markFailed(row.id());
                contentRepository.transitionShotStatus(row.shotId(), "GENERATING", "READY");
            }
            default -> { /* 不可能到达：扫描 SQL 已过滤终态 */ }
        }
    }
}
