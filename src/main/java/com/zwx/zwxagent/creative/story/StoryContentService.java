package com.zwx.zwxagent.creative.story;

import com.zwx.zwxagent.creative.domain.CandidateStatus;
import com.zwx.zwxagent.creative.domain.CreativeConflictException;
import com.zwx.zwxagent.creative.domain.VideoCharacterRecord;
import com.zwx.zwxagent.creative.domain.VideoContinuityNoteRecord;
import com.zwx.zwxagent.creative.domain.VideoEpisodeRecord;
import com.zwx.zwxagent.creative.domain.VideoLocationRecord;
import com.zwx.zwxagent.creative.domain.VideoSceneRecord;
import com.zwx.zwxagent.creative.domain.VideoShotCandidateRecord;
import com.zwx.zwxagent.creative.domain.VideoShotRecord;
import com.zwx.zwxagent.creative.repository.CreativeAssetRepository;
import com.zwx.zwxagent.creative.repository.VideoShotCandidateRepository;
import com.zwx.zwxagent.creative.repository.VideoStoryContentRepository;
import com.zwx.zwxagent.creative.repository.VideoStoryResourceRepository;
import com.zwx.zwxagent.creative.service.CreativeProjectService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 视频剧情域（P1/P2）：剧集/场次/镜头/角色/地点/连续性记录的内容管理。
 * 归属校验走 CreativeProjectService + 组合键查询；层级归属在服务层显式校验，
 * 组合外键在 DB 层兜底。镜头更新/选片使用 version 乐观锁（旧版本提交返回 409）。
 */
@Service
public class StoryContentService {

    public static final int TEXT_MAX = 20000;
    public static final int NAME_MAX = 100;

    private final CreativeProjectService projectService;
    private final VideoStoryContentRepository contentRepository;
    private final VideoStoryResourceRepository resourceRepository;
    private final VideoShotCandidateRepository candidateRepository;
    private final CreativeAssetRepository assetRepository;
    private final TransactionTemplate transactionTemplate;

    public StoryContentService(CreativeProjectService projectService,
                               VideoStoryContentRepository contentRepository,
                               VideoStoryResourceRepository resourceRepository,
                               VideoShotCandidateRepository candidateRepository,
                               CreativeAssetRepository assetRepository,
                               TransactionTemplate transactionTemplate) {
        this.projectService = projectService;
        this.contentRepository = contentRepository;
        this.resourceRepository = resourceRepository;
        this.candidateRepository = candidateRepository;
        this.assetRepository = assetRepository;
        this.transactionTemplate = transactionTemplate;
    }

    // ---------------------------------------------------------------- 视图

    /** 工作区一次取全：剧集/场次/镜头 + 角色/地点/连续性记录；候选按镜头汇总为计数与选定指针。 */
    public WorkspaceView workspace(CurrentActor actor, UUID projectId) {
        requireProject(actor, projectId);
        String tenantId = actor.tenantId();
        String userId = actor.userId();
        var episodes = contentRepository.listEpisodes(tenantId, userId, projectId);
        var scenes = contentRepository.listScenesByProject(tenantId, userId, projectId);
        var shots = contentRepository.listShotsByProject(tenantId, userId, projectId);
        Map<UUID, List<VideoShotCandidateRecord>> candidatesByShot =
                candidateRepository.listByProject(tenantId, userId, projectId).stream()
                        .collect(Collectors.groupingBy(VideoShotCandidateRecord::shotId));
        // 候选计数直接从已加载的候选分组统计，避免每个镜头一次 count 查询（N+1）
        var candidateCounts = candidatesByShot.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().size()));
        return new WorkspaceView(episodes, scenes, shots, candidatesByShot, candidateCounts,
                resourceRepository.listCharacters(tenantId, userId, projectId),
                resourceRepository.listLocations(tenantId, userId, projectId),
                resourceRepository.listNotes(tenantId, userId, projectId));
    }

    public record WorkspaceView(List<VideoEpisodeRecord> episodes,
                                List<VideoSceneRecord> scenes,
                                List<VideoShotRecord> shots,
                                Map<UUID, List<VideoShotCandidateRecord>> candidatesByShot,
                                Map<UUID, Integer> candidateCounts,
                                List<VideoCharacterRecord> characters,
                                List<VideoLocationRecord> locations,
                                List<VideoContinuityNoteRecord> notes) {
    }

    // ---------------------------------------------------------------- 剧集

    public VideoEpisodeRecord createEpisode(CurrentActor actor, UUID projectId, EpisodeRequest request) {
        requireProject(actor, projectId);
        validateText("剧集标题", request.title(), true);
        var record = new VideoEpisodeRecord(UUID.randomUUID(), projectId, actor.tenantId(), actor.userId(),
                contentRepository.nextEpisodeNumber(projectId),
                request.title().trim(), text(request.goal()), text(request.synopsis()), text(request.script()),
                "DRAFT", null, null);
        contentRepository.insertEpisode(record);
        return contentRepository.findEpisode(actor.tenantId(), actor.userId(), record.id()).orElseThrow();
    }

    public VideoEpisodeRecord updateEpisode(CurrentActor actor, UUID projectId, UUID episodeId,
                                            EpisodeRequest request) {
        requireEpisode(actor, projectId, episodeId);
        validateText("剧集标题", request.title(), true);
        if (!contentRepository.updateEpisode(episodeId, request.title().trim(),
                text(request.goal()), text(request.synopsis()), text(request.script()))) {
            throw new IllegalArgumentException("剧集不存在");
        }
        return contentRepository.findEpisode(actor.tenantId(), actor.userId(), episodeId).orElseThrow();
    }

    /** 确认剧集（DRAFT→CONFIRMED 单向，照小说章节语义）。 */
    public VideoEpisodeRecord confirmEpisode(CurrentActor actor, UUID projectId, UUID episodeId) {
        var episode = requireEpisode(actor, projectId, episodeId);
        if (!episode.confirmed()) {
            contentRepository.updateEpisodeStatus(episodeId, "CONFIRMED");
        }
        return contentRepository.findEpisode(actor.tenantId(), actor.userId(), episodeId).orElseThrow();
    }

    /** 剧集上移/下移：与相邻剧集交换顺序号（同事务，ID 不变；经临时号中转避免唯一索引冲突）。 */
    public List<VideoEpisodeRecord> moveEpisode(CurrentActor actor, UUID projectId, UUID episodeId, int direction) {
        var episodes = contentRepository.listEpisodes(actor.tenantId(), actor.userId(), projectId);
        int index = -1;
        for (int i = 0; i < episodes.size(); i++) {
            if (episodes.get(i).id().equals(episodeId)) index = i;
        }
        if (index < 0) throw new IllegalArgumentException("剧集不存在");
        int target = index + (direction > 0 ? 1 : -1);
        if (target < 0 || target >= episodes.size()) {
            return episodes; // 已在边界：原样返回
        }
        var current = episodes.get(index);
        var other = episodes.get(target);
        swapEpisodeNumbers(current, other);
        return contentRepository.listEpisodes(actor.tenantId(), actor.userId(), projectId);
    }

    /**
     * 顺序号交换：`uq_video_episode_number` 是立即唯一约束，两行直接互换会在第一条 UPDATE 处冲突；
     * 先把当前行挪到临时号（1_000_000 段），再依次落位。
     */
    private void swapEpisodeNumbers(VideoEpisodeRecord current, VideoEpisodeRecord other) {
        transactionTemplate.executeWithoutResult(status -> {
            contentRepository.updateEpisodeNumber(current.id(), 1_000_000 + other.episodeNumber());
            contentRepository.updateEpisodeNumber(other.id(), current.episodeNumber());
            contentRepository.updateEpisodeNumber(current.id(), other.episodeNumber());
        });
    }

    public void deleteEpisode(CurrentActor actor, UUID projectId, UUID episodeId) {
        var episode = requireEpisode(actor, projectId, episodeId);
        if (episode.confirmed()) {
            throw new CreativeConflictException("已确认剧集不可删除；如需调整请先取消确认语义的新建剧集承接");
        }
        // 级联：场次→镜头→候选（DB 外键不级联，逐层删除）
        var scenes = contentRepository.listScenesByProject(actor.tenantId(), actor.userId(), projectId).stream()
                .filter(scene -> scene.episodeId().equals(episodeId)).toList();
        for (var scene : scenes) {
            deleteSceneInternal(actor, scene);
        }
        contentRepository.deleteEpisode(episodeId);
    }

    // ---------------------------------------------------------------- 场次

    public VideoSceneRecord createScene(CurrentActor actor, UUID projectId, UUID episodeId,
                                        SceneRequest request) {
        requireEpisode(actor, projectId, episodeId);
        var record = new VideoSceneRecord(UUID.randomUUID(), projectId, actor.tenantId(), actor.userId(),
                episodeId, contentRepository.nextSceneNumber(episodeId),
                text(request.title()), text(request.locationText()), text(request.timeOfDay()),
                text(request.charactersText()), text(request.goal()), text(request.conflict()),
                text(request.entryState()), text(request.exitState()), "DRAFT", null, null);
        contentRepository.insertScene(record);
        return contentRepository.findScene(actor.tenantId(), actor.userId(), record.id()).orElseThrow();
    }

    public VideoSceneRecord updateScene(CurrentActor actor, UUID projectId, UUID sceneId,
                                        SceneRequest request) {
        var scene = requireScene(actor, projectId, sceneId);
        requireEpisode(actor, projectId, scene.episodeId());
        contentRepository.updateScene(sceneId, text(request.title()), text(request.locationText()),
                text(request.timeOfDay()), text(request.charactersText()), text(request.goal()),
                text(request.conflict()), text(request.entryState()), text(request.exitState()));
        return contentRepository.findScene(actor.tenantId(), actor.userId(), sceneId).orElseThrow();
    }

    public VideoSceneRecord confirmScene(CurrentActor actor, UUID projectId, UUID sceneId) {
        var scene = requireScene(actor, projectId, sceneId);
        contentRepository.updateSceneStatus(sceneId, "CONFIRMED");
        return contentRepository.findScene(actor.tenantId(), actor.userId(), scene.id()).orElseThrow();
    }

    public void deleteScene(CurrentActor actor, UUID projectId, UUID sceneId) {
        var scene = requireScene(actor, projectId, sceneId);
        deleteSceneInternal(actor, scene);
    }

    private void deleteSceneInternal(CurrentActor actor, VideoSceneRecord scene) {
        var shots = contentRepository.listShotsByProject(actor.tenantId(), actor.userId(), scene.projectId()).stream()
                .filter(shot -> shot.sceneId().equals(scene.id())).toList();
        for (var shot : shots) {
            candidateRepository.deleteByShot(shot.id());
            contentRepository.deleteShot(shot.id());
        }
        contentRepository.deleteScene(scene.id());
    }

    // ---------------------------------------------------------------- 镜头

    public VideoShotRecord createShot(CurrentActor actor, UUID projectId, UUID sceneId, ShotRequest request) {
        return createShot(actor, projectId, sceneId, request, null);
    }

    /**
     * 创建镜头；`afterShotId` 非空时插入到该镜头之后（同场次整体后移，单事务）。
     * `uq_video_shot_number` 是立即唯一约束：先整体 +1_000_000 挪出空间，插入占位后回填 -999_999。
     */
    public VideoShotRecord createShot(CurrentActor actor, UUID projectId, UUID sceneId,
                                      ShotRequest request, UUID afterShotId) {
        requireScene(actor, projectId, sceneId);
        int number;
        if (afterShotId == null) {
            number = contentRepository.nextShotNumber(sceneId);
        } else {
            var anchor = requireShot(actor, projectId, afterShotId);
            if (!anchor.sceneId().equals(sceneId)) {
                throw new IllegalArgumentException("插入位置镜头与目标场次不一致");
            }
            int afterNumber = anchor.shotNumber();
            transactionTemplate.executeWithoutResult(status -> contentRepository.shiftShotNumbers(
                    sceneId, afterNumber, 1_000_000));
            number = afterNumber + 1;
        }
        var shot = new VideoShotRecord(UUID.randomUUID(), projectId, actor.tenantId(), actor.userId(),
                sceneId, number,
                text(request.purpose()), text(request.action()), text(request.camera()),
                text(request.dialogue()), text(request.continuity()),
                duration(request.durationTargetSeconds()), text(request.promptText()),
                "DRAFT", 1L, null, null,
                normalizeShotStyle(request.styleCode()),
                request.referenceAssetIds() == null ? List.of() : request.referenceAssetIds());
        contentRepository.insertShot(shot);
        if (afterShotId != null) {
            transactionTemplate.executeWithoutResult(status ->
                    contentRepository.shiftShotNumbers(sceneId, 1_000_000, -999_999));
        }
        return contentRepository.findShot(actor.tenantId(), actor.userId(), shot.id()).orElseThrow();
    }

    /** 镜头上移/下移：同场次相邻交换顺序号（同事务，ID 不变，候选关系跟随镜头）。 */
    public List<VideoShotRecord> moveShot(CurrentActor actor, UUID projectId, UUID shotId, int direction) {
        var shot = requireShot(actor, projectId, shotId);
        var siblings = contentRepository.listShotsByProject(actor.tenantId(), actor.userId(), projectId).stream()
                .filter(item -> item.sceneId().equals(shot.sceneId()))
                .sorted(java.util.Comparator.comparingInt(VideoShotRecord::shotNumber))
                .toList();
        int index = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).id().equals(shotId)) index = i;
        }
        int target = index + (direction > 0 ? 1 : -1);
        if (target < 0 || target >= siblings.size()) {
            return siblings; // 已在边界：原样返回
        }
        var current = siblings.get(index);
        var other = siblings.get(target);
        transactionTemplate.executeWithoutResult(status -> {
            contentRepository.updateShotNumber(current.id(), 1_000_000 + other.shotNumber());
            contentRepository.updateShotNumber(other.id(), current.shotNumber());
            contentRepository.updateShotNumber(current.id(), other.shotNumber());
        });
        return contentRepository.listShotsByProject(actor.tenantId(), actor.userId(), projectId).stream()
                .filter(item -> item.sceneId().equals(shot.sceneId()))
                .sorted(java.util.Comparator.comparingInt(VideoShotRecord::shotNumber))
                .toList();
    }

    /** 镜头字段更新（乐观锁）：内容就绪（有 prompt 或动作细节）时置 READY，供生成按钮门禁。 */
    public VideoShotRecord updateShot(CurrentActor actor, UUID projectId, UUID shotId,
                                      long expectedVersion, ShotRequest request) {
        var shot = requireShot(actor, projectId, shotId);
        if (shot.version() != expectedVersion) {
            throw new CreativeConflictException("镜头已被其他页面修改（版本 " + shot.version()
                    + "），请刷新后重试；你的编辑未覆盖他人改动");
        }
        boolean ready = shot.confirmedReady() || !text(request.promptText()).isBlank();
        String styleCode = normalizeShotStyle(request.styleCode());
        String referenceJson = validateShotReference(actor, request.referenceAssetIds());
        if (!contentRepository.updateShotFields(shotId, expectedVersion,
                text(request.purpose()), text(request.action()), text(request.camera()),
                text(request.dialogue()), text(request.continuity()),
                duration(request.durationTargetSeconds()), text(request.promptText()),
                styleCode, referenceJson)) {
            throw new CreativeConflictException("镜头已被其他页面修改，请刷新后重试");
        }
        if (ready && "DRAFT".equals(shot.status())) {
            contentRepository.updateShotStatus(shotId, expectedVersion + 1, "READY");
        }
        return contentRepository.findShot(actor.tenantId(), actor.userId(), shotId).orElseThrow();
    }

    public void deleteShot(CurrentActor actor, UUID projectId, UUID shotId) {
        requireShot(actor, projectId, shotId);
        candidateRepository.deleteByShot(shotId);
        contentRepository.deleteShot(shotId);
    }

    public List<VideoShotRecord> listShots(CurrentActor actor, UUID projectId) {
        requireProject(actor, projectId);
        return contentRepository.listShotsByProject(actor.tenantId(), actor.userId(), projectId);
    }

    /** 生产闭环与抽屉需要读取镜头（归属校验后返回）。 */
    public VideoShotRecord getShot(CurrentActor actor, UUID projectId, UUID shotId) {
        return requireShot(actor, projectId, shotId);
    }

    /** 优化上下文等外部读取（归属校验后返回）。 */
    public VideoEpisodeRecord getEpisode(CurrentActor actor, UUID projectId, UUID episodeId) {
        return requireEpisode(actor, projectId, episodeId);
    }

    public VideoSceneRecord getScene(CurrentActor actor, UUID projectId, UUID sceneId) {
        return requireScene(actor, projectId, sceneId);
    }

    public List<VideoEpisodeRecord> episodes(CurrentActor actor, UUID projectId) {
        requireProject(actor, projectId);
        return contentRepository.listEpisodes(actor.tenantId(), actor.userId(), projectId);
    }

    public List<VideoSceneRecord> scenes(CurrentActor actor, UUID projectId) {
        requireProject(actor, projectId);
        return contentRepository.listScenesByProject(actor.tenantId(), actor.userId(), projectId);
    }

    // ---------------------------------------------------------------- 角色 / 地点 / 连续性

    public VideoCharacterRecord createCharacter(CurrentActor actor, UUID projectId, ResourceRequest request) {
        requireProject(actor, projectId);
        validateText("角色名", request.name(), true);
        var record = new VideoCharacterRecord(UUID.randomUUID(), projectId, actor.tenantId(), actor.userId(),
                request.name().trim(), text(request.appearance()), text(request.personality()),
                text(request.notes()), null, null);
        resourceRepository.insertCharacter(record);
        return resourceRepository.findCharacter(actor.tenantId(), actor.userId(), record.id()).orElseThrow();
    }

    public VideoCharacterRecord updateCharacter(CurrentActor actor, UUID projectId, UUID characterId,
                                                ResourceRequest request) {
        requireCharacter(actor, projectId, characterId);
        validateText("角色名", request.name(), true);
        resourceRepository.updateCharacter(characterId, request.name().trim(), text(request.appearance()),
                text(request.personality()), text(request.notes()));
        return resourceRepository.findCharacter(actor.tenantId(), actor.userId(), characterId).orElseThrow();
    }

    public void deleteCharacter(CurrentActor actor, UUID projectId, UUID characterId) {
        requireCharacter(actor, projectId, characterId);
        resourceRepository.deleteAssetRefsByEntity("CHARACTER", characterId);
        resourceRepository.deleteCharacter(characterId);
    }

    public VideoLocationRecord createLocation(CurrentActor actor, UUID projectId, ResourceRequest request) {
        requireProject(actor, projectId);
        validateText("地点名", request.name(), true);
        var record = new VideoLocationRecord(UUID.randomUUID(), projectId, actor.tenantId(), actor.userId(),
                request.name().trim(), text(request.description()), null, null);
        resourceRepository.insertLocation(record);
        return record;
    }

    public void updateLocation(CurrentActor actor, UUID projectId, UUID locationId,
                               ResourceRequest request) {
        requireProject(actor, projectId);
        validateText("地点名", request.name(), true);
        resourceRepository.updateLocation(locationId, request.name().trim(), text(request.description()));
    }

    public void deleteLocation(CurrentActor actor, UUID projectId, UUID locationId) {
        requireProject(actor, projectId);
        resourceRepository.deleteAssetRefsByEntity("LOCATION", locationId);
        resourceRepository.deleteLocation(locationId);
    }

    public VideoContinuityNoteRecord createNote(CurrentActor actor, UUID projectId, NoteRequest request) {
        requireProject(actor, projectId);
        validateText("记录内容", request.content(), true);
        var kind = normalizeKind(request.kind());
        var record = new VideoContinuityNoteRecord(UUID.randomUUID(), projectId, actor.tenantId(), actor.userId(),
                kind, request.content().trim(), text(request.sourceLabel()),
                foreshadowStatus(kind, request.foreshadowStatus()), null, null);
        resourceRepository.insertNote(record);
        return record;
    }

    public VideoContinuityNoteRecord updateNote(CurrentActor actor, UUID projectId, UUID noteId,
                                                NoteRequest request) {
        requireProject(actor, projectId);
        validateText("记录内容", request.content(), true);
        var kind = normalizeKind(request.kind());
        if (!resourceRepository.updateNote(noteId, request.content().trim(), text(request.sourceLabel()),
                foreshadowStatus(kind, request.foreshadowStatus()))) {
            throw new IllegalArgumentException("记录不存在");
        }
        return resourceRepository.listNotes(actor.tenantId(), actor.userId(), projectId).stream()
                .filter(note -> note.id().equals(noteId)).findFirst().orElseThrow();
    }

    public void deleteNote(CurrentActor actor, UUID projectId, UUID noteId) {
        requireProject(actor, projectId);
        resourceRepository.deleteNote(noteId);
    }

    public void attachReference(CurrentActor actor, UUID projectId, String entityType, UUID entityId, UUID assetId) {
        requireProject(actor, projectId);
        if (!"CHARACTER".equals(entityType) && !"LOCATION".equals(entityType)) {
            throw new IllegalArgumentException("不支持的参考对象类型");
        }
        resourceRepository.insertAssetRef(UUID.randomUUID(), projectId, actor.tenantId(), actor.userId(),
                entityType, entityId, assetId);
    }

    public void detachReference(CurrentActor actor, UUID projectId, String entityType, UUID entityId, UUID assetId) {
        requireProject(actor, projectId);
        resourceRepository.deleteAssetRef(entityType, entityId, assetId);
    }

    public List<UUID> listReferences(String entityType, UUID entityId) {
        return resourceRepository.listAssetRefs(entityType, entityId);
    }

    /** 素材反查：素材被哪些镜头候选引用（素材库「用于镜头」与删除保护展示）。 */
    public List<VideoShotCandidateRepository.CandidateUsageView> assetUsage(CurrentActor actor, UUID projectId,
                                                                            UUID assetId) {
        requireProject(actor, projectId);
        return candidateRepository.listUsageByAsset(actor.tenantId(), actor.userId(), projectId, assetId);
    }

    // ---------------------------------------------------------------- 校验辅助

    private void requireProject(CurrentActor actor, UUID projectId) {
        projectService.get(actor, projectId);
    }

    /** 批次等外部服务的项目归属校验入口。 */
    public void checkProject(CurrentActor actor, UUID projectId) {
        requireProject(actor, projectId);
    }

    private VideoEpisodeRecord requireEpisode(CurrentActor actor, UUID projectId, UUID episodeId) {
        var episode = contentRepository.findEpisode(actor.tenantId(), actor.userId(), episodeId)
                .orElseThrow(() -> new IllegalArgumentException("剧集不存在"));
        if (!episode.projectId().equals(projectId)) {
            throw new CreativeConflictException("剧集不属于该创作项目");
        }
        return episode;
    }

    private VideoSceneRecord requireScene(CurrentActor actor, UUID projectId, UUID sceneId) {
        var scene = contentRepository.findScene(actor.tenantId(), actor.userId(), sceneId)
                .orElseThrow(() -> new IllegalArgumentException("场次不存在"));
        if (!scene.projectId().equals(projectId)) {
            throw new CreativeConflictException("场次不属于该创作项目");
        }
        return scene;
    }

    private VideoShotRecord requireShot(CurrentActor actor, UUID projectId, UUID shotId) {
        var shot = contentRepository.findShot(actor.tenantId(), actor.userId(), shotId)
                .orElseThrow(() -> new IllegalArgumentException("镜头不存在"));
        if (!shot.projectId().equals(projectId)) {
            throw new CreativeConflictException("镜头不属于该创作项目");
        }
        return shot;
    }

    private VideoCharacterRecord requireCharacter(CurrentActor actor, UUID projectId, UUID characterId) {
        var character = resourceRepository.findCharacter(actor.tenantId(), actor.userId(), characterId)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
        if (!character.projectId().equals(projectId)) {
            throw new CreativeConflictException("角色不属于该创作项目");
        }
        return character;
    }

    private static String text(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() > TEXT_MAX) throw new IllegalArgumentException("内容超过 " + TEXT_MAX + " 字上限");
        return trimmed;
    }

    private static void validateText(String label, String value, boolean required) {
        String trimmed = value == null ? "" : value.trim();
        if (required && trimmed.isEmpty()) throw new IllegalArgumentException(label + "必填");
        if (trimmed.length() > NAME_MAX) throw new IllegalArgumentException(label + "超过 " + NAME_MAX + " 字上限");
    }

    private static int duration(Integer value) {
        if (value == null || value <= 0) return 4;
        return Math.min(value, 60);
    }

    private static String normalizeKind(String kind) {
        String value = kind == null ? "" : kind.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "FACT", "STATE", "FORESHADOW" -> value;
            default -> throw new IllegalArgumentException("不支持的记录类型：" + kind);
        };
    }

    private static String foreshadowStatus(String kind, String status) {
        if (!"FORESHADOW".equals(kind)) return "PENDING";
        String value = status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "PLANTED", "REINFORCED", "PENDING", "PAYOFF" -> value;
            default -> "PENDING";
        };
    }

    // ---------------------------------------------------------------- 请求体

    public record EpisodeRequest(String title, String goal, String synopsis, String script) {
    }

    public record SceneRequest(String title, String locationText, String timeOfDay, String charactersText,
                               String goal, String conflict, String entryState, String exitState) {
    }

    /** 镜头画风覆盖校验：空=继承项目；非空必须是目录编码（跟随原片对无首帧的镜头无意义，一并拒绝）。 */
    private String normalizeShotStyle(String styleCode) {
        String value = styleCode == null ? "" : styleCode.trim().toUpperCase(java.util.Locale.ROOT);
        if (value.isEmpty()) return "";
        if ("PHOTO_MATCH".equals(value)) {
            throw new IllegalArgumentException("镜头画风不支持「跟随原片」，请留空继承项目或选择其他风格");
        }
        com.zwx.zwxagent.creative.support.ImageStyleCatalog.require(value);
        return value;
    }

    /** 首帧参考校验：最多 1 个，必须是本人已就绪的图片素材；返回落库 JSON。 */
    private String validateShotReference(CurrentActor actor, java.util.List<UUID> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) return "[]";
        java.util.List<UUID> distinct = assetIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.size() > 1) {
            throw new IllegalArgumentException("首帧参考最多 1 张图片");
        }
        for (UUID assetId : distinct) {
            var asset = assetRepository.findByIdForOwner(actor.tenantId(), actor.userId(), assetId)
                    .orElseThrow(() -> new IllegalArgumentException("素材不存在或无权使用：" + assetId));
            if (!"IMAGE".equals(asset.mediaType())) {
                throw new IllegalArgumentException("首帧参考必须是图片素材");
            }
            if (!"READY".equals(asset.status().name())) {
                throw new IllegalArgumentException("首帧参考图片尚未就绪：" + assetId);
            }
        }
        return com.zwx.zwxagent.creative.support.CreativeJson.toJson(distinct);
    }

    public record ShotRequest(String purpose, String action, String camera, String dialogue,
                              String continuity, Integer durationTargetSeconds, String promptText,
                              String styleCode, List<UUID> referenceAssetIds) {

        /** 兼容旧调用方（7 参构造）：画风继承项目、无首帧。 */
        public ShotRequest(String purpose, String action, String camera, String dialogue,
                           String continuity, Integer durationTargetSeconds, String promptText) {
            this(purpose, action, camera, dialogue, continuity, durationTargetSeconds, promptText, "", List.of());
        }
    }

    public record ResourceRequest(String name, String appearance, String personality, String notes,
                                  String description) {
    }

    public record NoteRequest(String kind, String content, String sourceLabel, String foreshadowStatus) {
    }
}
