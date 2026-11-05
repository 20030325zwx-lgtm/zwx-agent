package com.zwx.zwxagent.creative.story;

import com.zwx.zwxagent.creative.domain.VideoEpisodeRecord;
import com.zwx.zwxagent.creative.domain.VideoSceneRecord;
import com.zwx.zwxagent.creative.service.PromptOptimizationService;
import com.zwx.zwxagent.creative.domain.CreativeConflictException;
import com.zwx.zwxagent.creative.service.CreativeProjectService;
import com.zwx.zwxagent.creative.support.ImageStyleCatalog;
import com.zwx.zwxagent.creative.service.PromptOptimizationService.OptimizeRequest;
import com.zwx.zwxagent.creative.service.PromptOptimizationService.StoryboardDraftRow;
import com.zwx.zwxagent.creative.support.CreativeJson;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.creative.repository.VideoStoryContentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 结构化分镜草稿（P0 制作视图，2026-11-05）：
 * 故事 → 结构化草稿 → 用户预览编辑 → 确认写入正式镜头。
 *
 * <p>关键语义（对应整改需求 §5.3 / §9.1）：
 * <ul>
 *   <li>草稿确认前与正式镜头隔离；解析失败保留 raw 文本，原故事与已有镜头不动；</li>
 *   <li>采纳用条件状态转移（READY→CONSUMED）保证同一草稿只写一次，重复点击/并发不重复建镜；</li>
 *   <li>写入前校验故事版本：草稿生成后故事被改动时要求重新确认来源版本；</li>
 *   <li>「替换未生成镜头」只删除 DRAFT/READY 且无候选的镜头，已有候选/成片引用的镜头保留并如实报告。</li>
 * </ul>
 * 剧集没有独立 version 列，用 updatedAt 纪元毫秒作为来源版本代理。</p>
 */
@Service
public class StoryboardDraftService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardDraftService.class);

    public static final String MODE_APPEND = "APPEND";
    public static final String MODE_REPLACE_UNGENERATED = "REPLACE_UNGENERATED";

    /** 一行草稿视图：rowId 是服务端生成的稳定行标识，前端编辑期间保持不变。 */
    public record DraftRowView(UUID rowId, int orderNo, int durationTargetSeconds, String purpose,
                               String action, String camera, String dialogue, String promptText) {
    }

    public record DraftView(UUID id, UUID projectId, UUID episodeId, UUID sceneId, String status,
                            long sourceEpisodeVersion, String episodeTitle, List<DraftRowView> rows,
                            String rawText, String modelName, String error, Instant createdAt) {
    }

    public record GenerateRequest(UUID episodeId, Integer targetSeconds) {
    }

    public record AdoptRequest(String mode, UUID sceneId, Long expectedSourceVersion, boolean force) {
    }

    public record AdoptResult(int created, int replaced, int keptExisting, List<UUID> shotIds) {
    }

    public record ShortfilmView(UUID episodeId, UUID sceneId, boolean created) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final StoryContentService storyContentService;
    private final VideoStoryContentRepository contentRepository;
    private final PromptOptimizationService optimizationService;
    private final CreativeProjectService projectService;

    public StoryboardDraftService(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                                  StoryContentService storyContentService,
                                  VideoStoryContentRepository contentRepository,
                                  PromptOptimizationService optimizationService,
                                  CreativeProjectService projectService) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.storyContentService = storyContentService;
        this.contentRepository = contentRepository;
        this.optimizationService = optimizationService;
        this.projectService = projectService;
    }

    // ---------------------------------------------------------------- 短片模式（D02：默认剧集/场次自动建立）

    /** 幂等建立「当前短片」默认剧集与第一场；已有剧集时返回第一集及其第一场（缺场次则补建）。 */
    public ShortfilmView ensureShortfilm(CurrentActor actor, UUID projectId) {
        storyContentService.checkProject(actor, projectId);
        List<VideoEpisodeRecord> episodes = contentRepository.listEpisodes(actor.tenantId(), actor.userId(), projectId);
        VideoEpisodeRecord episode;
        boolean created = false;
        if (episodes.isEmpty()) {
            episode = storyContentService.createEpisode(actor, projectId,
                    new StoryContentService.EpisodeRequest("当前短片", "", "", ""));
            created = true;
        } else {
            episode = episodes.getFirst();
        }
        VideoSceneRecord scene = firstScene(actor, projectId, episode.id());
        if (scene == null) {
            scene = storyContentService.createScene(actor, projectId, episode.id(),
                    new StoryContentService.SceneRequest("第 1 场", "", "", "", "", "", "", ""));
            created = true;
        }
        return new ShortfilmView(episode.id(), scene.id(), created);
    }

    private VideoSceneRecord firstScene(CurrentActor actor, UUID projectId, UUID episodeId) {
        return contentRepository.listScenesByProject(actor.tenantId(), actor.userId(), projectId).stream()
                .filter(scene -> scene.episodeId().equals(episodeId))
                .sorted(java.util.Comparator.comparingInt(VideoSceneRecord::sceneNumber))
                .findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- 生成与读取

    public DraftView generate(CurrentActor actor, UUID projectId, GenerateRequest request) {
        var ensured = ensureShortfilm(actor, projectId);
        UUID episodeId = request != null && request.episodeId() != null ? request.episodeId() : ensured.episodeId();
        var episode = contentRepository.findEpisode(actor.tenantId(), actor.userId(), episodeId)
                .orElseThrow(() -> new IllegalArgumentException("剧集不存在"));
        String story = !episode.script().isBlank() ? episode.script()
                : List.of(episode.goal(), episode.synopsis()).stream().filter(v -> !v.isBlank())
                        .reduce((a, b) -> a + "\n" + b).orElse("");
        if (story.isBlank()) {
            throw new IllegalArgumentException("故事内容为空，请先在「故事」阶段写下或粘贴剧本");
        }
        String targetNote = request != null && request.targetSeconds() != null && request.targetSeconds() > 0
                ? "\n\n目标总时长：约 " + request.targetSeconds() + " 秒（可上下浮动，保证叙事完整）" : "";
        // 项目画风作为补充注入：让每行 promptText 自带正确的风格语言，而不是让模型抄设定里的编码
        String styleNote = "";
        try {
            Object style = projectService.settingsOf(actor, projectId).get("videoStyle");
            if (style != null && !String.valueOf(style).isBlank()
                    && !"PHOTO_MATCH".equalsIgnoreCase(String.valueOf(style))) {
                styleNote = "\n\n画面风格：" + ImageStyleCatalog.videoNote(String.valueOf(style))
                        + "\n把该风格写进每行 promptText 的风格与质感描述里。";
            }
        } catch (RuntimeException ignored) {
            // 设定读取失败不阻断拆镜
        }
        var result = optimizationService.storyboardDraft(actor, new OptimizeRequest(
                story + targetNote + styleNote, PromptOptimizationService.MODE_VIDEO_STORYBOARD_DRAFT,
                projectId, episodeId, null, null, null, null));
        List<DraftRowView> rows = result.parsed() ? toRowViews(result.rows()) : List.of();
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO video_storyboard_draft
                    (id, project_id, tenant_id, user_id, episode_id, scene_id, source_episode_version,
                     status, rows, raw_text, model_name, error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """,
                id, projectId, actor.tenantId(), actor.userId(), episodeId, ensured.sceneId(),
                episode.updatedAt() == null ? 0L : episode.updatedAt().toEpochMilli(),
                result.parsed() ? "READY" : "PARSE_FAILED",
                CreativeJson.toJson(rows), result.raw() == null ? "" : result.raw(),
                result.modelName() == null ? "" : result.modelName(),
                result.error() == null ? "" : result.error());
        log.info("分镜草稿已保存：project={} draft={} status={} rows={}", projectId, id,
                result.parsed() ? "READY" : "PARSE_FAILED", rows.size());
        return get(actor, projectId, id);
    }

    public DraftView get(CurrentActor actor, UUID projectId, UUID draftId) {
        var rows = jdbcTemplate.query("""
                SELECT id, episode_id, scene_id, status, source_episode_version,
                       rows::text, raw_text, model_name, error, created_at
                FROM video_storyboard_draft
                WHERE id = ? AND project_id = ? AND tenant_id = ? AND user_id = ?
                """, (rs, rowNum) -> {
            UUID id = rs.getObject("id", UUID.class);
            UUID episodeId = rs.getObject("episode_id", UUID.class);
            UUID sceneId = rs.getObject("scene_id", UUID.class);
            String status = rs.getString("status");
            long sourceVersion = rs.getLong("source_episode_version");
            List<DraftRowView> draftRows = parseRows(rs.getString("rows"));
            String episodeTitle = contentRepository.findEpisode(actor.tenantId(), actor.userId(), episodeId)
                    .map(VideoEpisodeRecord::title).orElse("");
            return new DraftView(id, projectId, episodeId, sceneId, status, sourceVersion, episodeTitle,
                    draftRows, rs.getString("raw_text"), rs.getString("model_name"),
                    rs.getString("error"), rs.getTimestamp("created_at").toInstant());
        }, draftId, projectId, actor.tenantId(), actor.userId());
        if (rows.isEmpty()) throw new IllegalArgumentException("分镜草稿不存在");
        return rows.getFirst();
    }

    /** 放弃草稿：READY/PARSE_FAILED → DISCARDED；已采纳的草稿不可回退。 */
    public void discard(CurrentActor actor, UUID projectId, UUID draftId) {
        int updated = jdbcTemplate.update("""
                UPDATE video_storyboard_draft SET status = 'DISCARDED'
                WHERE id = ? AND project_id = ? AND tenant_id = ? AND user_id = ?
                  AND status IN ('READY', 'PARSE_FAILED')
                """, draftId, projectId, actor.tenantId(), actor.userId());
        if (updated == 0) {
            throw new IllegalArgumentException("草稿不存在或已处理");
        }
    }

    // ---------------------------------------------------------------- 采纳（一次有效写入）

    public AdoptResult adopt(CurrentActor actor, UUID projectId, UUID draftId, AdoptRequest request) {
        DraftView draft = get(actor, projectId, draftId);
        if (!"READY".equals(draft.status())) {
            throw new CreativeConflictException("该草稿已采纳或已失效，请重新生成分镜草稿");
        }
        if (draft.rows().isEmpty()) {
            throw new IllegalArgumentException("草稿没有可用镜头行");
        }
        String mode = request == null || request.mode() == null || request.mode().isBlank()
                ? MODE_APPEND : request.mode().trim().toUpperCase(java.util.Locale.ROOT);
        if (!MODE_APPEND.equals(mode) && !MODE_REPLACE_UNGENERATED.equals(mode)) {
            throw new IllegalArgumentException("不支持的采纳模式：" + request.mode());
        }
        long currentVersion = currentEpisodeVersion(actor, draft.episodeId());
        if (request != null && !request.force() && request.expectedSourceVersion() != null
                && request.expectedSourceVersion() != currentVersion) {
            throw new CreativeConflictException("故事在草稿生成后已被修改，请先确认基于最新故事（可重试生成分镜草稿）");
        }
        UUID sceneId = request != null && request.sceneId() != null ? request.sceneId() : draft.sceneId();
        UUID targetSceneId = sceneId != null ? sceneId : ensureShortfilm(actor, projectId).sceneId();
        // 条件状态转移：同一草稿只允许一次有效采纳（双击/重试/并发都落到这里）
        int consumed = jdbcTemplate.update(
                "UPDATE video_storyboard_draft SET status = 'CONSUMED' WHERE id = ? AND status = 'READY'", draftId);
        if (consumed == 0) {
            throw new CreativeConflictException("该草稿已采纳，请刷新镜头列表查看结果");
        }
        var result = new Object() {
            int replacedCount = 0;
            int keptCount = 0;
            final List<UUID> ids = new ArrayList<>();
        };
        transactionTemplate.executeWithoutResult(txStatus -> {
            if (MODE_REPLACE_UNGENERATED.equals(mode)) {
                var removable = jdbcTemplate.queryForList("""
                        SELECT s.id FROM video_shot s
                        WHERE s.scene_id = ? AND s.tenant_id = ? AND s.user_id = ?
                          AND s.status IN ('DRAFT', 'READY')
                          AND NOT EXISTS (SELECT 1 FROM video_shot_candidate c WHERE c.shot_id = s.id)
                        """, UUID.class, targetSceneId, actor.tenantId(), actor.userId());
                for (UUID shotId : removable) {
                    contentRepository.deleteShot(shotId);
                }
                result.replacedCount = removable.size();
            }
            result.keptCount = contentRepository.listShotsByProject(actor.tenantId(), actor.userId(), projectId).stream()
                    .filter(shot -> shot.sceneId().equals(targetSceneId)).toList().size();
            for (DraftRowView row : draft.rows()) {
                var shot = storyContentService.createShot(actor, projectId, targetSceneId,
                        new StoryContentService.ShotRequest(row.purpose(), row.action(), row.camera(),
                                row.dialogue(), "", row.durationTargetSeconds(), row.promptText()));
                result.ids.add(shot.id());
            }
        });
        log.info("分镜草稿采纳：project={} draft={} mode={} created={} replaced={}",
                projectId, draftId, mode, draft.rows().size(), result.replacedCount);
        return new AdoptResult(draft.rows().size(), result.replacedCount, result.keptCount, result.ids);
    }

    private long currentEpisodeVersion(CurrentActor actor, UUID episodeId) {
        return contentRepository.findEpisode(actor.tenantId(), actor.userId(), episodeId)
                .map(episode -> episode.updatedAt() == null ? 0L : episode.updatedAt().toEpochMilli())
                .orElse(0L);
    }

    private List<DraftRowView> toRowViews(List<StoryboardDraftRow> rows) {
        List<DraftRowView> views = new ArrayList<>();
        int order = 0;
        for (StoryboardDraftRow row : rows) {
            order++;
            views.add(new DraftRowView(UUID.randomUUID(), row.orderNo() <= 0 ? order : row.orderNo(),
                    row.durationTargetSeconds(), row.purpose(), row.action(), row.camera(),
                    row.dialogue(), row.promptText()));
        }
        return views;
    }

    @SuppressWarnings("unchecked")
    private List<DraftRowView> parseRows(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<DraftRowView> rows = CreativeJson.fromJson(json, new com.fasterxml.jackson.core.type.TypeReference<>() { });
            return rows == null ? List.of() : rows;
        } catch (Exception exception) {
            return List.of();
        }
    }
}
