package com.zwx.zwxagent.creative.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zwx.zwxagent.creative.domain.CreativeException;
import com.zwx.zwxagent.creative.domain.ParameterSchema;
import com.zwx.zwxagent.creative.domain.VideoContinuityNoteRecord;
import com.zwx.zwxagent.creative.domain.VideoEpisodeRecord;
import com.zwx.zwxagent.creative.domain.VideoSceneRecord;
import com.zwx.zwxagent.creative.domain.VideoShotRecord;
import com.zwx.zwxagent.creative.repository.VideoStoryResourceRepository;
import com.zwx.zwxagent.creative.story.StoryContentService;
import com.zwx.zwxagent.creative.support.CreativeJson;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.settings.DynamicChatModelFactory;
import com.zwx.zwxagent.platform.settings.ModelCatalogService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 提示词优化（P0 重构，2026-11-01）：按模式分流策略，不再所有输入都走文生图改写。
 * - IMAGE：沿用旧「文生图提示词编辑」行为与响应字段（兼容现有调用方）。
 * - VIDEO_PROMPT 优化生成提示词：按 Seedance 指南把镜头意图改写为可提交文本（动作部位/幅度/速度、
 *   单镜头一种运镜、起始→主要→结束、衔接方向），可注入项目/镜头上下文（服务端按 ID 读取，不信任客户端拼装）。
 * - VIDEO_STORY 优化剧情 / VIDEO_STORYBOARD 细化分镜：返回结构化建议文本。
 * 三种模式都只做文本改写：不自动触发生成、不改已确认事实；结构化输出解析失败如实报错，不用纯文本兜底冒充结构。
 */
@Service
public class PromptOptimizationService {

    public static final int PROMPT_MAX = 8000;

    public static final String MODE_IMAGE = "IMAGE";
    public static final String MODE_VIDEO_PROMPT = "VIDEO_PROMPT";
    public static final String MODE_VIDEO_STORY = "VIDEO_STORY";
    public static final String MODE_VIDEO_STORYBOARD = "VIDEO_STORYBOARD";

    private static final String SYSTEM_IMAGE = """
            你是文生图提示词编辑。把用户描述改写为一段可直接用于图片生成模型的中文提示词：
            主体、场景、构图、光线、风格要具体，不添加用户未要求的文字水印或品牌。
            只输出 JSON：{"prompt":"改写后的提示词"}，不要输出解释或 Markdown。
            """;

    /** 视频提示词策略（依据火山 Seedance 2.0 提示词指南，2026-11-01 调研）。 */
    private static final String SYSTEM_VIDEO_PROMPT = """
            你是文生视频提示词编辑。把镜头意图改写为一段可直接提交给视频生成模型的中文提示词，规则：
            1. 动作具体到部位、方向、速度与幅度（手指收紧/脚步停住/缓慢转头），用可见动作表达情绪；
            2. 单镜头只用一种主运镜（推/拉/摇/移/跟/固定选其一），景别与角度明确；
            3. 按事件顺序描述：起始姿态 → 主要动作 → 结束状态；单镜头负荷不要过载；
            4. 结尾说明与下一镜头的衔接方向（如“视线固定在画面右侧”）；
            5. 只描述画面内可见内容：身份背景/心理/伏笔不写入；声音未确认就不写声音；
            6. 不用“高清 8K”等画质承诺词；不添加用户未要求的水印品牌；
            7. 输出长度不超过 500 字。
            只输出 JSON：{"optimizedText":"提交文本","changes":["改了什么"],"suggestions":["可选建议"],"missing":["缺失待补项"]}，
            不要输出解释或 Markdown。
            """;

    private static final String SYSTEM_VIDEO_STORY = """
            你是短剧剧情编辑。基于给定上下文（作品设定/连续性记录/本集与场次信息）优化剧情草稿，规则：
            1. 补强因果与动机，指出冲突/转折/伏笔的落点与节奏问题；
            2. 不得改写「已确认事实/状态」，与记录冲突时如实指出并给选项；
            3. 缺失信息列为 missing（明确说明是建议，不是已有设定）；
            4. 不编造项目里不存在的角色/地点。
            只输出 JSON：{"optimizedText":"优化后的剧情稿","changes":[],"suggestions":[],"missing":[]}，
            不要输出解释或 Markdown。
            """;

    private static final String SYSTEM_VIDEO_STORYBOARD = """
            你是短剧分镜师。把已确认场次拆解为分镜草稿（文本），规则：
            1. 每个镜头给出：镜号建议、剧情目的、主体与站位、动作细节（部位/方向/速度）、
               景别/角度/主运镜、衔接方向、目标时长（秒）；
            2. 相邻镜头动作承接（上一镜头结束=下一镜头开始）；
            3. 复杂动作拆镜，单镜头不过载；
            4. 台词/声音未确认时标注为待补项。
            只输出 JSON：{"optimizedText":"分镜草稿（逐镜头列出，可用换行）","changes":[],"suggestions":[],"missing":[]}，
            不要输出解释或 Markdown。
            """;

    public record OptimizeRequest(String prompt, String mode, UUID projectId,
                                  UUID episodeId, UUID sceneId, UUID shotId) {
    }

    /**
     * IMAGE 模式沿用旧响应字段（originalPrompt/optimizedPrompt/modelName）；
     * 视频模式额外返回 changes/suggestions/missing（可对比与部分参考，原文始终保留）。
     */
    public record OptimizeResult(String originalPrompt, String optimizedPrompt, String modelName,
                                 List<String> changes, List<String> suggestions, List<String> missing) {

        /** 旧图片调用方的兼容构造（无结构化建议）。 */
        public static OptimizeResult legacy(String originalPrompt, String optimizedPrompt, String modelName) {
            return new OptimizeResult(originalPrompt, optimizedPrompt, modelName, List.of(), List.of(), List.of());
        }
    }

    private final ModelCatalogService catalogService;
    private final DynamicChatModelFactory chatModelFactory;
    private final CreativeProjectService projectService;
    private final StoryContentService storyContentService;
    private final VideoStoryResourceRepository resourceRepository;

    public PromptOptimizationService(ModelCatalogService catalogService,
                                     DynamicChatModelFactory chatModelFactory,
                                     CreativeProjectService projectService,
                                     StoryContentService storyContentService,
                                     VideoStoryResourceRepository resourceRepository) {
        this.catalogService = catalogService;
        this.chatModelFactory = chatModelFactory;
        this.projectService = projectService;
        this.storyContentService = storyContentService;
        this.resourceRepository = resourceRepository;
    }

    public OptimizeResult optimize(CurrentActor actor, OptimizeRequest request) {
        String userId = actor.userId();
        String mode = normalizeMode(request == null ? null : request.mode());
        String prompt = request == null ? null : request.prompt();
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("请输入要优化的内容");
        }
        String trimmed = prompt.trim();
        if (trimmed.length() > PROMPT_MAX) {
            throw CreativeException.unsupportedParameter("内容超过 " + PROMPT_MAX + " 个字符");
        }
        String system;
        String userContent;
        if (MODE_IMAGE.equals(mode)) {
            system = SYSTEM_IMAGE;
            userContent = trimmed;
        } else {
            var context = buildContext(actor, mode, request);
            system = switch (mode) {
                case MODE_VIDEO_STORY -> SYSTEM_VIDEO_STORY;
                case MODE_VIDEO_STORYBOARD -> SYSTEM_VIDEO_STORYBOARD;
                default -> SYSTEM_VIDEO_PROMPT;
            };
            userContent = trimmed + (context.isBlank() ? "" : "\n\n" + context);
        }
        var selection = catalogService.resolveWithFallback(
                userId, ModelCatalogService.PURPOSE_OPTIMIZE, ModelCatalogService.PURPOSE_CHAT);
        String raw;
        try {
            ChatClient client = ChatClient.builder(chatModelFactory.model(selection.config())).build();
            raw = client.prompt().system(system).user(userContent).call().content();
        } catch (Exception exception) {
            throw CreativeException.optimizeFailed("提示词优化失败：" + rootMessage(exception));
        }
        if (MODE_IMAGE.equals(mode)) {
            String optimized = extractPrompt(raw);
            if (optimized.isBlank()) {
                throw CreativeException.optimizeFailed("优化结果为空，请重试或直接使用原文");
            }
            if (optimized.length() > ParameterSchema.MODEL_PROMPT_MAX) {
                throw CreativeException.optimizeFailed(
                        "优化结果超过模型上限 " + ParameterSchema.MODEL_PROMPT_MAX + " 字，请缩短描述后重试");
            }
            return OptimizeResult.legacy(trimmed, optimized, selection.config().modelName());
        }
        // 视频模式：结构化 JSON 必须合法，不用纯文本兜底冒充结构完整
        var structured = extractStructured(raw);
        String optimized = structured.optimizedText().trim();
        if (optimized.isBlank()) {
            throw CreativeException.optimizeFailed("优化结果为空，请重试或直接使用原文");
        }
        // 提交文本受模型 500 字上限；剧情/分镜稿是创作文档，只受请求级上限约束
        if (MODE_VIDEO_PROMPT.equals(mode) && optimized.length() > ParameterSchema.MODEL_PROMPT_MAX) {
            throw CreativeException.optimizeFailed(
                    "优化结果超过模型上限 " + ParameterSchema.MODEL_PROMPT_MAX + " 字，请缩短描述后重试");
        }
        if (optimized.length() > PROMPT_MAX) {
            throw CreativeException.optimizeFailed("优化结果超过 " + PROMPT_MAX + " 字上限");
        }
        return new OptimizeResult(trimmed, optimized, selection.config().modelName(),
                structured.changes(), structured.suggestions(), structured.missing());
    }

    // ---------------------------------------------------------------- 上下文组装（服务端按 ID 定向读取）

    /** 上下文信任边界：服务端按 ID 定向读取并校验归属，不信任客户端拼装的内容。 */
    private String buildContext(CurrentActor actor, String mode, OptimizeRequest request) {
        UUID projectId = request.projectId();
        if (projectId == null) return "";
        projectService.get(actor, projectId);
        var blocks = new StringBuilder();
        var settings = projectService.settingsOf(actor, projectId);
        appendBlock(blocks, "作品设定", settings);
        if (request.shotId() != null) {
            appendShotContext(blocks, actor, projectId, request.shotId());
        } else if (request.sceneId() != null) {
            appendSceneContext(blocks, actor, projectId, request.sceneId());
        } else if (request.episodeId() != null) {
            appendEpisodeContext(blocks, actor, projectId, request.episodeId());
        }
        appendCharactersAndLocations(blocks, actor, projectId);
        appendContinuity(blocks, actor, projectId);
        return blocks.toString().trim();
    }

    private void appendCharactersAndLocations(StringBuilder blocks, CurrentActor actor, UUID projectId) {
        var characters = resourceRepository.listCharacters(actor.tenantId(), actor.userId(), projectId);
        if (!characters.isEmpty()) {
            blocks.append("\n【角色】");
            for (var character : characters) {
                blocks.append("\n- ").append(character.name())
                        .append(character.appearance().isBlank() ? "" : "：" + character.appearance());
            }
        }
        var locations = resourceRepository.listLocations(actor.tenantId(), actor.userId(), projectId);
        if (!locations.isEmpty()) {
            blocks.append("\n【地点】");
            for (var location : locations) {
                blocks.append("\n- ").append(location.name())
                        .append(location.description().isBlank() ? "" : "：" + location.description());
            }
        }
    }

    private void appendEpisodeContext(StringBuilder blocks, CurrentActor actor, UUID projectId, UUID episodeId) {
        var episode = requireEpisode(actor, projectId, episodeId);
        blocks.append("\n【剧集】第 ").append(episode.episodeNumber()).append(" 集《").append(episode.title())
                .append("》\n本集目标：").append(orDash(episode.goal()))
                .append("\n梗概：").append(orDash(episode.synopsis()));
    }

    private void appendSceneContext(StringBuilder blocks, CurrentActor actor, UUID projectId, UUID sceneId) {
        var scene = requireScene(actor, projectId, sceneId);
        var episode = requireEpisode(actor, projectId, scene.episodeId());
        blocks.append("\n【场次】第 ").append(episode.episodeNumber()).append(" 集 / 第 ").append(scene.sceneNumber())
                .append(" 场 ").append(scene.title().isBlank() ? "" : "《" + scene.title() + "》")
                .append("\n地点/时间：").append(orDash(scene.locationText())).append(" / ").append(orDash(scene.timeOfDay()))
                .append("\n出场人物：").append(orDash(scene.charactersText()))
                .append("\n场次目标：").append(orDash(scene.goal()))
                .append("\n冲突：").append(orDash(scene.conflict()))
                .append("\n场前状态：").append(orDash(scene.entryState()))
                .append("\n场后状态：").append(orDash(scene.exitState()));
    }

    private void appendShotContext(StringBuilder blocks, CurrentActor actor, UUID projectId, UUID shotId) {
        VideoShotRecord shot = storyContentService.getShot(actor, projectId, shotId);
        var scene = requireScene(actor, projectId, shot.sceneId());
        appendSceneContext(blocks, actor, projectId, scene.id());
        blocks.append("\n【本镜头】第 ").append(shot.shotNumber()).append(" 镜")
                .append("\n叙事目的：").append(orDash(shot.purpose()))
                .append("\n动作/表演：").append(orDash(shot.action()))
                .append("\n景别/机位/运镜：").append(orDash(shot.camera()))
                .append("\n对白：").append(orDash(shot.dialogue()))
                .append("\n衔接/连续性：").append(orDash(shot.continuity()))
                .append("\n目标时长：").append(shot.durationTargetSeconds()).append(" 秒");
    }

    private void appendContinuity(StringBuilder blocks, CurrentActor actor, UUID projectId) {
        var notes = resourceRepository.listNotes(actor.tenantId(), actor.userId(), projectId);
        if (notes.isEmpty()) return;
        blocks.append("\n【连续性记录】");
        for (var note : notes) {
            String kindLabel = switch (note.kind()) {
                case "FACT" -> "事实";
                case "STATE" -> "状态";
                default -> "伏笔(" + note.foreshadowStatus() + ")";
            };
            blocks.append("\n- [").append(kindLabel).append("] ").append(note.content());
            if (!note.sourceLabel().isBlank()) blocks.append("（来源：").append(note.sourceLabel()).append("）");
        }
    }

    private static void appendBlock(StringBuilder blocks, String label, Map<String, Object> settings) {
        if (settings == null || settings.isEmpty()) return;
        StringBuilder content = new StringBuilder();
        for (var entry : settings.entrySet()) {
            Object value = entry.getValue();
            if (value == null || String.valueOf(value).isBlank()) continue;
            if (content.length() > 0) content.append("\n");
            content.append(entry.getKey()).append("：").append(value);
        }
        if (content.length() > 0) blocks.append("\n【").append(label).append("】\n").append(content);
    }

    // ---------------------------------------------------------------- 解析

    /** 容忍模型包裹代码块或多余文本：取首个 JSON 对象的 prompt 字段（IMAGE 模式，C09 兼容）。 */
    static String extractPrompt(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                var node = CreativeJson.parse(text.substring(start, end + 1));
                String value = node.path("prompt").asText("").trim();
                if (!value.isBlank()) return value;
            } catch (Exception ignored) {
                // 非 JSON：按纯文本处理
            }
        }
        // 模型直接返回纯文本时接受，但剔除代码围栏
        return text.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
    }

    record StructuredResult(String optimizedText, List<String> changes, List<String> suggestions,
                            List<String> missing) {
    }

    /** 视频模式解析：JSON 非法如实失败（不再用纯文本兜底冒充结构完整）。 */
    static StructuredResult extractStructured(String raw) {
        if (raw == null) throw CreativeException.optimizeFailed("优化结果不是合法的结构化输出");
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw CreativeException.optimizeFailed("优化结果不是合法的结构化输出");
        }
        try {
            var node = CreativeJson.parse(text.substring(start, end + 1));
            String optimized = node.path("optimizedText").asText("").trim();
            if (optimized.isBlank()) {
                throw CreativeException.optimizeFailed("优化结果缺少提交文本");
            }
            return new StructuredResult(optimized,
                    readList(node, "changes"), readList(node, "suggestions"), readList(node, "missing"));
        } catch (CreativeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw CreativeException.optimizeFailed("优化结果不是合法的结构化输出");
        }
    }

    private static List<String> readList(com.fasterxml.jackson.databind.JsonNode node, String field) {
        try {
            List<String> values = CreativeJson.fromJson(
                    CreativeJson.toJson(node.path(field)), new TypeReference<List<String>>() { });
            return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static String normalizeMode(String mode) {
        String value = mode == null || mode.isBlank() ? MODE_IMAGE : mode.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case MODE_IMAGE, MODE_VIDEO_PROMPT, MODE_VIDEO_STORY, MODE_VIDEO_STORYBOARD -> value;
            default -> throw new IllegalArgumentException("不支持的优化模式：" + mode);
        };
    }

    // ---------------------------------------------------------------- 归属校验（上下文信任边界）

    private VideoEpisodeRecord requireEpisode(CurrentActor actor, UUID projectId, UUID episodeId) {
        return storyContentService.getEpisode(actor, projectId, episodeId);
    }

    private VideoSceneRecord requireScene(CurrentActor actor, UUID projectId, UUID sceneId) {
        return storyContentService.getScene(actor, projectId, sceneId);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "（暂无）" : value;
    }

    private static String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
