package com.zwx.zwxagent.creative.story;

import com.zwx.zwxagent.creative.domain.CreativeAssetRecord;
import com.zwx.zwxagent.creative.domain.VideoFinalCutRecord;
import static com.zwx.zwxagent.creative.domain.VideoFinalCutRecord.VideoFinalCutItem;
import com.zwx.zwxagent.creative.domain.VideoShotCandidateRecord;
import com.zwx.zwxagent.creative.domain.VideoShotRecord;
import com.zwx.zwxagent.creative.repository.CreativeAssetRepository;
import com.zwx.zwxagent.creative.repository.VideoFinalCutRepository;
import com.zwx.zwxagent.creative.repository.VideoShotCandidateRepository;
import com.zwx.zwxagent.creative.repository.VideoStoryContentRepository;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 成片草稿与导出编排（P2 最小闭环）：
 * - 草稿按镜头顺序取每镜「已采用」候选；缺片镜头如实返回（阻止完整导出，由用户决定仅导出已选片段）；
 * - 导出从草稿快照出运行行，幂等键防重复提交（A08 同族）；片段冻结，期间编辑不改变在途成片；
 * - 取消/重试走条件状态转移；下载走统一素材链路（签名 URL）。
 */
@Service
public class FinalCutService {

    private static final Logger log = LoggerFactory.getLogger(FinalCutService.class);
    private static final List<String> OUTPUT_SIZES = List.of("1280x720", "720x1280", "1920x1080", "1080x1920", "720x720");

    public record MissingShot(int shotNumber, String reason) {
    }

    public record DraftView(VideoFinalCutRecord cut, List<MissingShot> missing) {
    }

    public record ExportRequest(String idempotencyKey, String outputSize) {
    }

    public record UpdateItemsRequest(long expectedItemsVersion, List<ItemInput> items) {
    }

    public record ItemInput(UUID shotId, UUID candidateId, UUID assetId) {
    }

    private final VideoFinalCutRepository cutRepository;
    private final VideoStoryContentRepository contentRepository;
    private final VideoShotCandidateRepository candidateRepository;
    private final CreativeAssetRepository assetRepository;

    public FinalCutService(VideoFinalCutRepository cutRepository,
                           VideoStoryContentRepository contentRepository,
                           VideoShotCandidateRepository candidateRepository,
                           CreativeAssetRepository assetRepository) {
        this.cutRepository = cutRepository;
        this.contentRepository = contentRepository;
        this.candidateRepository = candidateRepository;
        this.assetRepository = assetRepository;
    }

    /** 依据镜头顺序与已采用候选重建草稿（覆盖旧草稿）。 */
    public DraftView buildDraft(CurrentActor actor, UUID projectId) {
        List<VideoShotRecord> shots = contentRepository.listShotsByProject(
                actor.tenantId(), actor.userId(), projectId);
        List<VideoShotCandidateRecord> candidates = candidateRepository.listByProject(
                actor.tenantId(), actor.userId(), projectId);
        List<VideoFinalCutItem> items = new ArrayList<>();
        List<MissingShot> missing = new ArrayList<>();
        for (VideoShotRecord shot : shots) {
            var selected = candidates.stream()
                    .filter(candidate -> shot.id().equals(candidate.shotId()))
                    .filter(candidate -> "SELECTED".equals(candidate.status()))
                    .findFirst();
            if (selected.isEmpty() || selected.get().assetId() == null) {
                missing.add(new MissingShot(shot.shotNumber(),
                        "SELECTED".equals(shot.status()) ? "候选素材不可用" : "尚未选片"));
                continue;
            }
            var asset = assetRepository.findByIdForOwner(actor.tenantId(), actor.userId(), selected.get().assetId())
                    .orElse(null);
            if (asset == null || !"READY".equals(asset.status().name())) {
                missing.add(new MissingShot(shot.shotNumber(), "视频素材不可读取"));
                continue;
            }
            items.add(new VideoFinalCutItem(shot.id(), shot.shotNumber(), selected.get().id(),
                    asset.id(), asset.displayName(), asset.durationMs()));
        }
        cutRepository.latestDraft(actor.tenantId(), actor.userId(), projectId)
                .ifPresent(draft -> cutRepository.deleteDraft(draft.id()));
        var draft = new VideoFinalCutRecord(UUID.randomUUID(), projectId, actor.tenantId(), actor.userId(),
                "DRAFT", items, 1L, null, null, null, null, "", null, null, null, null);
        cutRepository.insert(draft);
        log.info("成片草稿已重建：project={} items={} missing={}", projectId, items.size(), missing.size());
        return new DraftView(draft, missing);
    }

    public DraftView draft(CurrentActor actor, UUID projectId) {
        var draft = cutRepository.latestDraft(actor.tenantId(), actor.userId(), projectId).orElse(null);
        if (draft == null) return buildDraft(actor, projectId);
        return new DraftView(draft, List.of());
    }

    public VideoFinalCutRecord get(CurrentActor actor, UUID projectId, UUID cutId) {
        return cutRepository.find(actor.tenantId(), actor.userId(), cutId)
                .orElseThrow(() -> new IllegalArgumentException("成片记录不存在"));
    }

    public List<VideoFinalCutRecord> list(CurrentActor actor, UUID projectId, int limit) {
        return cutRepository.listByProject(actor.tenantId(), actor.userId(), projectId, Math.min(limit, 50));
    }

    /** 草稿片段编辑：排序/移除/手动加入素材；素材必须属于本人且已就绪。 */
    public VideoFinalCutRecord updateItems(CurrentActor actor, UUID projectId, UUID cutId,
                                           UpdateItemsRequest request) {
        var cut = get(actor, projectId, cutId);
        if (!cut.isDraft()) throw new IllegalArgumentException("只有草稿可以编辑片段");
        List<VideoFinalCutItem> items = new ArrayList<>();
        Set<UUID> seenAssets = new HashSet<>();
        for (ItemInput input : request == null ? List.<ItemInput>of() : request.items()) {
            if (input.assetId() == null || !seenAssets.add(input.assetId())) continue;
            var asset = assetRepository.findByIdForOwner(actor.tenantId(), actor.userId(), input.assetId())
                    .orElseThrow(() -> new IllegalArgumentException("素材不存在或无权使用"));
            if (!"VIDEO".equals(asset.mediaType()) || !"READY".equals(asset.status().name())) {
                throw new IllegalArgumentException("只能加入本人已就绪的视频素材");
            }
            int shotNumber = input.shotId() == null ? 0 : shotNumberOf(contentRepository, actor, projectId, input.shotId());
            items.add(new VideoFinalCutItem(input.shotId(), shotNumber, input.candidateId(),
                    asset.id(), asset.displayName(), asset.durationMs()));
        }
        if (items.size() > 50) throw new IllegalArgumentException("片段数超过 50 上限");
        if (!cutRepository.updateDraftItems(cutId, request.expectedItemsVersion(), items)) {
            throw new com.zwx.zwxagent.creative.domain.CreativeConflictException(
                    "成片草稿已被其他页面修改，请刷新后重试");
        }
        return get(actor, projectId, cutId);
    }

    private static int shotNumberOf(VideoStoryContentRepository repository, CurrentActor actor,
                                    UUID projectId, UUID shotId) {
        return repository.listShotsByProject(actor.tenantId(), actor.userId(), projectId).stream()
                .filter(shot -> shot.id().equals(shotId))
                .mapToInt(VideoShotRecord::shotNumber).findFirst().orElse(0);
    }

    /** 提交导出：幂等键相同直接返回已有运行（双击/重试不重复渲染）；片段冻结为快照。 */
    public VideoFinalCutRecord export(CurrentActor actor, UUID projectId, UUID draftId, ExportRequest request) {
        var draft = get(actor, projectId, draftId);
        if (!draft.isDraft()) throw new IllegalArgumentException("请从成片草稿发起导出");
        if (draft.items().isEmpty()) throw new IllegalArgumentException("草稿没有片段，先完成选片或手动加入素材");
        String size = request == null || request.outputSize() == null || request.outputSize().isBlank()
                ? "1280x720" : request.outputSize().trim().toLowerCase(Locale.ROOT);
        if (!OUTPUT_SIZES.contains(size)) throw new IllegalArgumentException("输出画幅不支持：" + size);
        String key = request == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                ? "fc-" + draft.id() + "-" + draft.itemsVersion()
                : request.idempotencyKey().trim();
        var existing = cutRepository.listByProject(actor.tenantId(), actor.userId(), projectId, 50).stream()
                .filter(row -> key.equals(row.idempotencyKey())).findFirst();
        if (existing.isPresent()) return existing.get();
        for (VideoFinalCutItem item : draft.items()) {
            var asset = assetRepository.findByIdForOwner(actor.tenantId(), actor.userId(), item.assetId())
                    .orElseThrow(() -> new IllegalStateException(
                            "镜 " + item.shotNumber() + " 的视频素材不存在，请重新选片"));
            if (!"READY".equals(asset.status().name())) {
                throw new IllegalStateException("镜 " + item.shotNumber() + " 的视频暂不可读取，请重新选片或刷新素材状态");
            }
        }
        var run = new VideoFinalCutRecord(UUID.randomUUID(), projectId, actor.tenantId(), actor.userId(),
                "PREPARING", draft.items(), draft.itemsVersion(), null, null, null, size, "",
                draft.id(), key, null, null);
        cutRepository.insert(run);
        log.info("成片导出已提交：project={} cut={} clips={} size={}", projectId, run.id(),
                draft.items().size(), size);
        return run;
    }

    public VideoFinalCutRecord cancel(CurrentActor actor, UUID projectId, UUID cutId) {
        var cut = get(actor, projectId, cutId);
        if (!cut.isRunning()) throw new IllegalArgumentException("该导出不在进行中");
        cutRepository.markCancelled(cutId);
        return get(actor, projectId, cutId);
    }

    public VideoFinalCutRecord retry(CurrentActor actor, UUID projectId, UUID cutId) {
        var cut = get(actor, projectId, cutId);
        if (!"FAILED".equals(cut.status())) throw new IllegalArgumentException("只有失败的导出可以重试");
        if (!cutRepository.markRunningForRetry(cutId)) {
            throw new com.zwx.zwxagent.creative.domain.CreativeConflictException("导出状态已变化，请刷新后重试");
        }
        return get(actor, projectId, cutId);
    }

    /** 首个可用输出素材（下载/播放走统一素材链路）。 */
    public java.util.Optional<CreativeAssetRecord> outputAsset(CurrentActor actor, UUID projectId, UUID cutId) {
        var cut = get(actor, projectId, cutId);
        if (cut.outputAssetId() == null) return java.util.Optional.empty();
        return assetRepository.findByIdForOwner(actor.tenantId(), actor.userId(), cut.outputAssetId());
    }
}
