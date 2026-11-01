package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.creative.domain.VideoCharacterRecord;
import com.zwx.zwxagent.creative.domain.VideoContinuityNoteRecord;
import com.zwx.zwxagent.creative.domain.VideoEpisodeRecord;
import com.zwx.zwxagent.creative.domain.VideoLocationRecord;
import com.zwx.zwxagent.creative.domain.VideoBatchRecord;
import com.zwx.zwxagent.creative.domain.VideoSceneRecord;
import com.zwx.zwxagent.creative.domain.VideoShotRecord;
import com.zwx.zwxagent.creative.story.BatchProductionService;
import com.zwx.zwxagent.creative.story.ShotProductionService;
import com.zwx.zwxagent.creative.story.StoryContentService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 视频剧情域 API（P1/P2）：剧集/场次/镜头/角色/地点/连续性记录 + 镜头候选与选片。
 * 归属校验在服务层（CurrentActor → 组合键），错误由全局 ApiExceptionHandler 映射。
 */
@RestController
@RequestMapping("/ai/creative/projects/{projectId}/story")
public class StoryController {

    private final StoryContentService contentService;
    private final ShotProductionService productionService;
    private final BatchProductionService batchProductionService;

    public StoryController(StoryContentService contentService, ShotProductionService productionService,
                           BatchProductionService batchProductionService) {
        this.contentService = contentService;
        this.productionService = productionService;
        this.batchProductionService = batchProductionService;
    }

    // ---------------------------------------------------------------- 工作区

    @GetMapping("/workspace")
    public StoryContentService.WorkspaceView workspace(CurrentActor actor, @PathVariable UUID projectId) {
        return contentService.workspace(actor, projectId);
    }

    // ---------------------------------------------------------------- 剧集

    @PostMapping("/episodes")
    public VideoEpisodeRecord createEpisode(CurrentActor actor, @PathVariable UUID projectId,
                                            @RequestBody StoryContentService.EpisodeRequest body) {
        return contentService.createEpisode(actor, projectId, body);
    }

    @PatchMapping("/episodes/{episodeId}")
    public VideoEpisodeRecord updateEpisode(CurrentActor actor, @PathVariable UUID projectId,
                                            @PathVariable UUID episodeId,
                                            @RequestBody StoryContentService.EpisodeRequest body) {
        return contentService.updateEpisode(actor, projectId, episodeId, body);
    }

    @PostMapping("/episodes/{episodeId}/confirm")
    public VideoEpisodeRecord confirmEpisode(CurrentActor actor, @PathVariable UUID projectId,
                                             @PathVariable UUID episodeId) {
        return contentService.confirmEpisode(actor, projectId, episodeId);
    }

    @PostMapping("/episodes/{episodeId}/move")
    public List<VideoEpisodeRecord> moveEpisode(CurrentActor actor, @PathVariable UUID projectId,
                                                @PathVariable UUID episodeId,
                                                @RequestBody Map<String, Integer> body) {
        int direction = body == null ? 0 : body.getOrDefault("direction", 0);
        return contentService.moveEpisode(actor, projectId, episodeId, direction);
    }

    @DeleteMapping("/episodes/{episodeId}")
    public Map<String, Object> deleteEpisode(CurrentActor actor, @PathVariable UUID projectId,
                                             @PathVariable UUID episodeId) {
        contentService.deleteEpisode(actor, projectId, episodeId);
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- 场次

    @PostMapping("/episodes/{episodeId}/scenes")
    public VideoSceneRecord createScene(CurrentActor actor, @PathVariable UUID projectId,
                                        @PathVariable UUID episodeId,
                                        @RequestBody StoryContentService.SceneRequest body) {
        return contentService.createScene(actor, projectId, episodeId, body);
    }

    @PatchMapping("/scenes/{sceneId}")
    public VideoSceneRecord updateScene(CurrentActor actor, @PathVariable UUID projectId,
                                        @PathVariable UUID sceneId,
                                        @RequestBody StoryContentService.SceneRequest body) {
        return contentService.updateScene(actor, projectId, sceneId, body);
    }

    @PostMapping("/scenes/{sceneId}/confirm")
    public VideoSceneRecord confirmScene(CurrentActor actor, @PathVariable UUID projectId,
                                         @PathVariable UUID sceneId) {
        return contentService.confirmScene(actor, projectId, sceneId);
    }

    @DeleteMapping("/scenes/{sceneId}")
    public Map<String, Object> deleteScene(CurrentActor actor, @PathVariable UUID projectId,
                                           @PathVariable UUID sceneId) {
        contentService.deleteScene(actor, projectId, sceneId);
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- 镜头

    @GetMapping("/shots/{shotId}")
    public VideoShotRecord getShot(CurrentActor actor, @PathVariable UUID projectId,
                                   @PathVariable UUID shotId) {
        return contentService.getShot(actor, projectId, shotId);
    }

    @PostMapping("/scenes/{sceneId}/shots")
    public VideoShotRecord createShot(CurrentActor actor, @PathVariable UUID projectId,
                                      @PathVariable UUID sceneId,
                                      @RequestBody StoryContentService.ShotRequest body) {
        return contentService.createShot(actor, projectId, sceneId, body);
    }

    @PatchMapping("/shots/{shotId}")
    public VideoShotRecord updateShot(CurrentActor actor, @PathVariable UUID projectId,
                                      @PathVariable UUID shotId,
                                      @RequestBody UpdateShotBody body) {
        var request = new StoryContentService.ShotRequest(body.purpose(), body.action(), body.camera(),
                body.dialogue(), body.continuity(), body.durationTargetSeconds(), body.promptText());
        return contentService.updateShot(actor, projectId, shotId,
                body.expectedVersion() == null ? -1 : body.expectedVersion(), request);
    }

    public record UpdateShotBody(Long expectedVersion, String purpose, String action, String camera,
                                 String dialogue, String continuity, Integer durationTargetSeconds,
                                 String promptText) {
    }

    @DeleteMapping("/shots/{shotId}")
    public Map<String, Object> deleteShot(CurrentActor actor, @PathVariable UUID projectId,
                                          @PathVariable UUID shotId) {
        contentService.deleteShot(actor, projectId, shotId);
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- 候选与选片

    @GetMapping("/shots/{shotId}/candidates")
    public List<ShotProductionService.CandidateView> candidates(CurrentActor actor, @PathVariable UUID projectId,
                                                                @PathVariable UUID shotId) {
        return productionService.candidates(actor, projectId, shotId);
    }

    @PostMapping("/shots/{shotId}/generate")
    public ShotProductionService.CandidateView generate(CurrentActor actor, @PathVariable UUID projectId,
                                                        @PathVariable UUID shotId,
                                                        @RequestBody ShotProductionService.GenerateRequest body) {
        return productionService.generate(actor, projectId, shotId,
                body == null ? new ShotProductionService.GenerateRequest(null, null) : body);
    }

    @PostMapping("/shots/{shotId}/candidates/upload")
    public ShotProductionService.CandidateView uploadCandidate(CurrentActor actor, @PathVariable UUID projectId,
                                                               @PathVariable UUID shotId,
                                                               @RequestBody Map<String, UUID> body) {
        UUID assetId = body == null ? null : body.get("assetId");
        if (assetId == null) throw new IllegalArgumentException("缺少 assetId");
        return productionService.addUploadCandidate(actor, projectId, shotId, assetId);
    }

    @PostMapping("/shots/{shotId}/select")
    public ShotProductionService.CandidateView select(CurrentActor actor, @PathVariable UUID projectId,
                                                      @PathVariable UUID shotId,
                                                      @RequestBody ShotProductionService.SelectRequest body) {
        return productionService.select(actor, projectId, shotId, body);
    }

    @PostMapping("/shots/{shotId}/unselect")
    public ShotProductionService.CandidateView unselect(CurrentActor actor, @PathVariable UUID projectId,
                                                        @PathVariable UUID shotId,
                                                        @RequestBody Map<String, UUID> body) {
        UUID candidateId = body == null ? null : body.get("candidateId");
        if (candidateId == null) throw new IllegalArgumentException("缺少 candidateId");
        return productionService.unselect(actor, projectId, shotId, candidateId);
    }

    @DeleteMapping("/shots/{shotId}/candidates/{candidateId}")
    public Map<String, Object> deleteCandidate(CurrentActor actor, @PathVariable UUID projectId,
                                               @PathVariable UUID shotId, @PathVariable UUID candidateId) {
        productionService.deleteCandidate(actor, projectId, shotId, candidateId);
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- 批次生产（P3）

    @PostMapping("/batches")
    public VideoBatchRecord createBatch(CurrentActor actor, @PathVariable UUID projectId,
                                        @RequestBody BatchProductionService.CreateBatchRequest body) {
        return batchProductionService.createBatch(actor, projectId,
                body == null ? new BatchProductionService.CreateBatchRequest(List.of(), "") : body);
    }

    @GetMapping("/batches")
    public List<VideoBatchRecord> batches(CurrentActor actor, @PathVariable UUID projectId) {
        return batchProductionService.list(actor, projectId);
    }

    @GetMapping("/batches/{batchId}")
    public BatchProductionService.BatchView batch(CurrentActor actor, @PathVariable UUID projectId,
                                                  @PathVariable UUID batchId) {
        return batchProductionService.view(actor, projectId, batchId);
    }

    @PostMapping("/batches/{batchId}/stop")
    public VideoBatchRecord stopBatch(CurrentActor actor, @PathVariable UUID projectId,
                                      @PathVariable UUID batchId) {
        return batchProductionService.stop(actor, projectId, batchId);
    }

    @PostMapping("/batches/{batchId}/retry-failed")
    public VideoBatchRecord retryBatch(CurrentActor actor, @PathVariable UUID projectId,
                                       @PathVariable UUID batchId) {
        return batchProductionService.retryFailed(actor, projectId, batchId);
    }

    // ---------------------------------------------------------------- 角色 / 地点 / 连续性

    @PostMapping("/characters")
    public VideoCharacterRecord createCharacter(CurrentActor actor, @PathVariable UUID projectId,
                                                @RequestBody StoryContentService.ResourceRequest body) {
        return contentService.createCharacter(actor, projectId, body);
    }

    @PatchMapping("/characters/{characterId}")
    public VideoCharacterRecord updateCharacter(CurrentActor actor, @PathVariable UUID projectId,
                                                @PathVariable UUID characterId,
                                                @RequestBody StoryContentService.ResourceRequest body) {
        return contentService.updateCharacter(actor, projectId, characterId, body);
    }

    @DeleteMapping("/characters/{characterId}")
    public Map<String, Object> deleteCharacter(CurrentActor actor, @PathVariable UUID projectId,
                                               @PathVariable UUID characterId) {
        contentService.deleteCharacter(actor, projectId, characterId);
        return Map.of("ok", true);
    }

    @PostMapping("/locations")
    public VideoLocationRecord createLocation(CurrentActor actor, @PathVariable UUID projectId,
                                              @RequestBody StoryContentService.ResourceRequest body) {
        return contentService.createLocation(actor, projectId, body);
    }

    @PatchMapping("/locations/{locationId}")
    public Map<String, Object> updateLocation(CurrentActor actor, @PathVariable UUID projectId,
                                              @PathVariable UUID locationId,
                                              @RequestBody StoryContentService.ResourceRequest body) {
        contentService.updateLocation(actor, projectId, locationId, body);
        return Map.of("ok", true);
    }

    @DeleteMapping("/locations/{locationId}")
    public Map<String, Object> deleteLocation(CurrentActor actor, @PathVariable UUID projectId,
                                              @PathVariable UUID locationId) {
        contentService.deleteLocation(actor, projectId, locationId);
        return Map.of("ok", true);
    }

    @GetMapping("/notes")
    public List<VideoContinuityNoteRecord> notes(CurrentActor actor, @PathVariable UUID projectId) {
        return contentService.workspace(actor, projectId).notes();
    }

    @PostMapping("/notes")
    public VideoContinuityNoteRecord createNote(CurrentActor actor, @PathVariable UUID projectId,
                                                @RequestBody StoryContentService.NoteRequest body) {
        return contentService.createNote(actor, projectId, body);
    }

    @PatchMapping("/notes/{noteId}")
    public VideoContinuityNoteRecord updateNote(CurrentActor actor, @PathVariable UUID projectId,
                                                @PathVariable UUID noteId,
                                                @RequestBody StoryContentService.NoteRequest body) {
        return contentService.updateNote(actor, projectId, noteId, body);
    }

    @DeleteMapping("/notes/{noteId}")
    public Map<String, Object> deleteNote(CurrentActor actor, @PathVariable UUID projectId,
                                          @PathVariable UUID noteId) {
        contentService.deleteNote(actor, projectId, noteId);
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- 参考图与素材反查

    @PostMapping("/refs")
    public Map<String, Object> attachRef(CurrentActor actor, @PathVariable UUID projectId,
                                         @RequestBody Map<String, Object> body) {
        String entityType = String.valueOf(body == null ? null : body.get("entityType"));
        UUID entityId = uuidOf(body == null ? null : body.get("entityId"));
        UUID assetId = uuidOf(body == null ? null : body.get("assetId"));
        contentService.attachReference(actor, projectId, entityType, entityId, assetId);
        return Map.of("ok", true);
    }

    @DeleteMapping("/refs")
    public Map<String, Object> detachRef(CurrentActor actor, @PathVariable UUID projectId,
                                         @RequestParam String entityType, @RequestParam UUID entityId,
                                         @RequestParam UUID assetId) {
        contentService.detachReference(actor, projectId, entityType, entityId, assetId);
        return Map.of("ok", true);
    }

    @GetMapping("/asset-usage")
    public List<VideoShotCandidateRepositoryUsageView> assetUsage(CurrentActor actor, @PathVariable UUID projectId,
                                                                  @RequestParam UUID assetId) {
        return contentService.assetUsage(actor, projectId, assetId).stream()
                .map(view -> new VideoShotCandidateRepositoryUsageView(view.candidateId(), view.shotId(),
                        view.status(), view.episodeNumber(), view.episodeTitle(), view.sceneNumber(),
                        view.shotNumber()))
                .toList();
    }

    /** 素材反查视图（用于素材库「用于镜头」徽标与详情）。 */
    public record VideoShotCandidateRepositoryUsageView(UUID candidateId, UUID shotId, String status,
                                                        int episodeNumber, String episodeTitle,
                                                        int sceneNumber, int shotNumber) {
    }

    private static UUID uuidOf(Object value) {
        if (value == null) throw new IllegalArgumentException("缺少 ID 参数");
        return UUID.fromString(String.valueOf(value));
    }
}
