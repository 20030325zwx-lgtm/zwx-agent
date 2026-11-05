package com.zwx.zwxagent.creative.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zwx.zwxagent.creative.asset.CreativeAssetService;
import com.zwx.zwxagent.creative.domain.CreativeException;
import com.zwx.zwxagent.creative.domain.ParameterSchema;
import com.zwx.zwxagent.creative.domain.VideoContinuityNoteRecord;
import com.zwx.zwxagent.creative.domain.VideoEpisodeRecord;
import com.zwx.zwxagent.creative.domain.VideoSceneRecord;
import com.zwx.zwxagent.creative.domain.VideoShotRecord;
import com.zwx.zwxagent.creative.repository.VideoStoryResourceRepository;
import com.zwx.zwxagent.creative.story.StoryContentService;
import com.zwx.zwxagent.creative.support.ImageStyleCatalog;
import com.zwx.zwxagent.creative.support.VideoPromptGuide;
import com.zwx.zwxagent.creative.support.CreativeJson;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.settings.DynamicChatModelFactory;
import com.zwx.zwxagent.platform.settings.ModelCatalogService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 提示词优化（P0 重构，2026-11-01）：按模式分流策略，不再所有输入都走文生图改写。
 * - IMAGE：沿用旧「文生图提示词编辑」行为与响应字段（兼容现有调用方）；风格取自
 *   {@link ImageStyleCatalog}（摄影取向 + 漫画），其中 PHOTO_MATCH 会读取参考图并沿用其镜头/光线/调色。
 * - VIDEO_PROMPT 优化生成提示词：按 Seedance 指南把镜头意图改写为可提交文本（动作部位/幅度/速度、
 *   单镜头一种运镜、起始→主要→结束、衔接方向），可注入项目/镜头上下文（服务端按 ID 读取，不信任客户端拼装）。
 * - VIDEO_STORY 优化剧情 / VIDEO_STORYBOARD 细化分镜：返回结构化建议文本。
 * 三种模式都只做文本改写：不自动触发生成、不改已确认事实；结构化输出解析失败如实报错，不用纯文本兜底冒充结构。
 */
@Service
public class PromptOptimizationService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PromptOptimizationService.class);

    public static final int PROMPT_MAX = 8000;

    public static final String MODE_IMAGE = "IMAGE";
    public static final String MODE_VIDEO_PROMPT = "VIDEO_PROMPT";
    public static final String MODE_VIDEO_STORY = "VIDEO_STORY";
    public static final String MODE_VIDEO_STORYBOARD = "VIDEO_STORYBOARD";
    public static final String MODE_VIDEO_STORYBOARD_DRAFT = "VIDEO_STORYBOARD_DRAFT";

    /** 图片风格：取自风格目录；写实/漫画保留旧常量名以兼容既有调用方。 */
    public static final String IMAGE_STYLE_REALISTIC = ImageStyleCatalog.REALISTIC;
    public static final String IMAGE_STYLE_COMIC = ImageStyleCatalog.COMIC;
    public static final String IMAGE_STYLE_PHOTO_MATCH = ImageStyleCatalog.PHOTO_MATCH;

    /** 参考图上限：只用于风格分析，两张足够（主参考 + 备选），避免多图抬高时延与费用。 */
    private static final int MAX_REFERENCE_IMAGES = 2;
    private static final long MAX_REFERENCE_BYTES = 8L * 1024 * 1024;

    private static final String IMAGE_COMMON_RULES = """
            只输出 JSON：{"prompt":"改写后的提示词"}，不要输出解释或 Markdown。
            改写要求：保留用户原意与关键约束，不新增用户未要求的元素、文字、水印或品牌；
            提示词需可直接提交给图片生成模型，控制在 300 字以内，信息密度高、少用空泛形容词。
            """;

    /** 写实摄影规范（贴合照片）：主体质感、镜头语言、光线与真实感约束。 */
    private static final String SYSTEM_IMAGE_REALISTIC = """
            你是摄影方向的文生图提示词编辑。把用户描述改写为一段可直接生成「写实照片」的中文提示词，规范：
            1. 主体：年龄/性别/发型/体型，服装面料与褶皱，皮肤质感（毛孔、细绒毛、自然瑕疵），自然姿态与微表情；
            2. 环境：真实地点与材质、天气、时段（阴天、黄金时刻、夜晚现场光等），避免抽象背景；
            3. 镜头：等效焦段（24mm 环境 / 35mm 纪实 / 85mm 人像特写）、光圈与景深（如 f/1.8 浅景深）、拍摄角度与构图（三分法、引导线、平视或俯拍）；
            4. 光线：光源类型与方向（窗光侧逆光、柔光箱、现场混合光）、色温、阴影软硬；
            5. 质感：胶片或数码取向（柔和肤色过渡的胶片质感 / 清晰自然的数码质感）、轻微颗粒或适度动态模糊、自然色彩分级；
            6. 真实感约束：避免插画感、3D 渲染感、塑料皮肤与 AI 感；五官与手指自然。
            """ + IMAGE_COMMON_RULES;

    /** 漫画/插画规范：子风格、线稿、赛璐璐上色、漫画语言与风格统一约束。 */
    private static final String SYSTEM_IMAGE_COMIC = """
            你是漫画/插画方向的文生图提示词编辑。把用户描述改写为一段可直接生成「漫画风插画」的中文提示词，规范：
            1. 先定子风格并贯穿全图：日漫（少年/少女/青年）、国漫、美式漫画或韩漫 webtoon，线条与上色随子风格统一；
            2. 线稿：干净有力的勾线、粗细变化与墨线描边（ink outline），关键轮廓加粗；
            3. 上色：赛璐璐平涂与块面阴影（硬边缘）、简洁高光；可按子风格采用有限色板与氛围色；
            4. 漫画语言：需要时加入网点（screentone）、速度线、集中线；不生成文字或对白气泡内的文字；
            5. 构图：强透视、镜头感明确，角色姿态有支撑点、不僵硬；
            6. 风格约束：避免照片质感、真实皮肤毛孔与 3D 渲染感，线条风格必须统一。
            """ + IMAGE_COMMON_RULES;

    /**
     * 摄影风格通用骨架（2026-11-05）：主体/环境/镜头/光线四段保持通用写法，
     * 具体取向由风格目录的 rubric 注入，新增风格不需要再写一份完整系统提示词。
     */
    private static final String SYSTEM_IMAGE_PHOTO_TEMPLATE = """
            你是摄影方向的文生图提示词编辑。把用户描述改写为一段可直接生成「照片」的中文提示词，规范：
            1. 主体：身份/年龄/服装面料与褶皱、皮肤与毛发质感（毛孔、细绒毛、自然瑕疵），自然姿态与微表情；
            2. 环境：真实地点与材质、天气、时段，避免抽象背景；
            3. 镜头：等效焦段（24mm 环境 / 35mm 纪实 / 50mm 自然 / 85mm 人像 / 100mm 微距）、光圈与景深、机位与构图；
            4. 光线：光源类型与方向、色温、阴影软硬；
            5. 质感与调色：严格按下方「风格锚点」执行，不混入其他风格的特征；
            6. 真实感约束：避免插画感、3D 渲染感、塑料皮肤与 AI 感，五官与手指自然。
            【风格锚点】%s
            """ + IMAGE_COMMON_RULES;

    /**
     * 3D 渲染骨架（2026-11-05）：造型/材质/灯光/渲染/镜头五段，
     * 具体取向（卡通、国漫、黏土、手办）由风格锚点注入。
     */
    private static final String SYSTEM_IMAGE_CG_TEMPLATE = """
            你是 3D 渲染方向的文生图提示词编辑。把用户描述改写为一段可直接生成「3D 渲染画面」的中文提示词，规范：
            1. 造型：比例与体积关系、轮廓特征、服装与道具的立体结构，风格化程度按锚点执行；
            2. 材质：皮肤次表面散射、毛发/布料的蓬松与垂坠、金属/玻璃/陶瓷的反射与粗糙度；
            3. 灯光：主光方向与软硬、环境光与补光、体积光与轮廓光，控制高光形状；
            4. 渲染：景深与虚化、轻微辉光或色差、干净通透的配色，画面像电影级 CG 而非照片；
            5. 镜头：等效焦段与机位、构图纵深，避免平铺直叙的正面展示；
            6. 风格约束：严格按下方「风格锚点」执行，不混入其他渲染取向，不出现文字与水印。
            【风格锚点】%s
            """ + IMAGE_COMMON_RULES;

    /**
     * 2D 插画骨架（2026-11-05）：媒介/线条/上色/构图四段，
     * 漫画、日系动画、国风、水彩、油画、水墨、像素、线稿共用，差异全在锚点。
     */
    private static final String SYSTEM_IMAGE_ILLUSTRATION_TEMPLATE = """
            你是插画方向的文生图提示词编辑。把用户描述改写为一段可直接生成「2D 插画」的中文提示词，规范：
            1. 媒介：先确定画种与工具（赛璐璐动画、工笔、岩彩、水彩、油画、水墨、像素、铅笔线稿），全图统一；
            2. 线条：勾线的粗细与收放、轮廓强调程度；无勾线的画种说明笔触与块面关系；
            3. 上色：上色方式（平涂/晕染/厚涂/抖动渐变）、色板与明暗层次、高光处理；
            4. 构图：透视与景别、留白与视觉重心，动作姿态有支撑点；
            5. 风格约束：严格按下方「风格锚点」执行，不混入照片质感或其他画种特征，不生成文字与水印。
            【风格锚点】%s
            """ + IMAGE_COMMON_RULES;

    /**
     * 结构化拆镜草稿（2026-11-05 P0）：一次生成可校验的镜头行集合，确认后才写入正式镜头。
     * 稳定行标识由服务端采纳时生成，草稿阶段只保顺序；模型不得编造已有 assetId。
     */
    private static final String SYSTEM_VIDEO_STORYBOARD_DRAFT = """
            你是短剧分镜师。基于给定的故事/剧本与上下文，拆解为结构化分镜草稿。规则：
            1. 每个镜头一行，字段：durationTargetSeconds（目标秒数，1-60 的整数）、
               purpose（剧情目的）、action（画面与动作细节：主体站位、部位/方向/速度）、
               camera（景别/角度/主运镜）、dialogue（台词或旁白，没有则空字符串）、
               promptText（可直接提交给视频模型的生成提示词，按主体+动作+环境+镜头+光线色调+风格+质感组织）；
            2. 相邻镜头动作承接（上一镜头结束=下一镜头开始），复杂动作拆镜；
            3. 行数 1-40；单个短片建议 2-12 行；目标时长合计尽量贴近用户给定的总时长；
            4. 角色/场景只引用上下文里已存在的名字，不得编造素材或 assetId；不生成文字水印；
            5. 拆分约束无法满足时写进 missing，不要虚构。
            只输出 JSON：{"shots":[...],"suggestions":["可选建议"],"missing":["缺失待补项"]}，
            不要输出解释或 Markdown。
            """;

    /** 跟随原片：参考图只用来提取风格要素，不复述其内容，避免把上一张的主体带进新图。 */
    private static final String SYSTEM_IMAGE_PHOTO_MATCH = """
            你是摄影风格分析与文生图提示词编辑。用户会附带一张参考照片和一段文字描述，规则：
            1. 先读参考片的风格要素：镜头（焦段感/景深/透视）、光线（方向/软硬/色温）、
               色彩与调色（饱和度/对比/高光与阴影倾向/偏色）、质感（胶片颗粒或数码锐度）、构图（取景与留白）；
            2. 改写后的提示词必须沿用这套风格要素，让新画面看起来是同一个人、同一台机器、同一套调色拍出来的续作；
            3. 只提取风格，不复述参考片里的具体人物、物体、场景内容，避免把上一张的内容带进新图；
            4. 用户文字描述优先；描述与参考片冲突时以文字为准，但保持风格一致；
            5. 参考片信息不足时按「自然光 + 50mm + 中性调色」补齐，不编造品牌、文字或水印。
            """ + IMAGE_COMMON_RULES;

    /**
     * 视频提示词策略（2026-11-04 起）：复用 {@link VideoPromptGuide} 的七要素固化规则，
     * 与内置「视频提示词优化师」技能同一套标准，避免按钮与技能给出两套口径；仅输出契约不同。
     */
    private static final String SYSTEM_VIDEO_PROMPT = VideoPromptGuide.RUBRIC + """

            只输出 JSON：{"optimizedText":"提交文本","changes":["实际改动"],"suggestions":["可选建议"],"missing":["缺失待补项"]}，
            不要输出解释或 Markdown。changes 每条以“主体/动作/环境/镜头/光线/风格/质感/删改/保留/拆镜”开头。
            本入口只返回一条主提示词；若原文需要拆成多镜，optimizedText 给首镜，拆分方案写入 suggestions。
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

    /**
     * imageStyle 在 IMAGE 模式决定图片风格骨架；在 VIDEO_PROMPT 模式作为画面风格注记
     * 写进 [风格]/[质感]（空值表示不指定，保持原有自由发挥）。referenceAssetIds 供「跟随原片」
     * 读取参考图，服务端按 ID 校验归属后读取，不信任客户端传入的图片内容或地址。
     */
    public record OptimizeRequest(String prompt, String mode, UUID projectId,
                                  UUID episodeId, UUID sceneId, UUID shotId, String imageStyle,
                                  List<UUID> referenceAssetIds) {
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
    private final CreativeAssetService assetService;

    public PromptOptimizationService(ModelCatalogService catalogService,
                                     DynamicChatModelFactory chatModelFactory,
                                     CreativeProjectService projectService,
                                     StoryContentService storyContentService,
                                     VideoStoryResourceRepository resourceRepository,
                                     CreativeAssetService assetService) {
        this.catalogService = catalogService;
        this.chatModelFactory = chatModelFactory;
        this.projectService = projectService;
        this.storyContentService = storyContentService;
        this.resourceRepository = resourceRepository;
        this.assetService = assetService;
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
        boolean matchReference = false;
        if (MODE_IMAGE.equals(mode)) {
            String style = normalizeImageStyle(request == null ? null : request.imageStyle());
            matchReference = IMAGE_STYLE_PHOTO_MATCH.equals(style);
            system = imageSystemPrompt(style);
            userContent = trimmed;
        } else {
            var context = buildContext(actor, mode, request);
            system = switch (mode) {
                case MODE_VIDEO_STORY -> SYSTEM_VIDEO_STORY;
                case MODE_VIDEO_STORYBOARD -> SYSTEM_VIDEO_STORYBOARD;
                default -> SYSTEM_VIDEO_PROMPT;
            };
            // 视频画风（2026-11-05）：只影响 [风格]/[质感] 两个要素，不改主体、动作与运镜
            String styleNote = MODE_VIDEO_PROMPT.equals(mode)
                    ? videoStyleNote(request.imageStyle()) : "";
            if (!styleNote.isBlank()) {
                system = system + "\n【画面风格】" + styleNote
                        + "\n把该风格写进 [风格] 与 [质感]，不要改变用户指定的主体、动作、运镜与台词。";
            }
            userContent = trimmed + (context.isBlank() ? "" : "\n\n" + context);
        }
        List<ReferenceImage> referenceImages = matchReference
                ? loadReferenceImages(actor, request.referenceAssetIds())
                : List.of();
        if (matchReference && referenceImages.isEmpty()) {
            throw new IllegalArgumentException("「跟随原片」需要先连接或添加一张参考图片");
        }
        var selection = matchReference
                ? resolveVisionSelection(userId)
                : catalogService.resolveWithFallback(
                        userId, ModelCatalogService.PURPOSE_OPTIMIZE, ModelCatalogService.PURPOSE_CHAT);
        log.info("提示词优化：mode={} style={} model={} provider={} 参考图={} 张",
                mode, request == null ? null : request.imageStyle(),
                selection.config().modelName(), selection.config().provider(), referenceImages.size());
        String raw;
        try {
            ChatClient client = ChatClient.builder(referenceImages.isEmpty()
                    ? chatModelFactory.model(selection.config())
                    : chatModelFactory.multimodalModel(selection.config())).build();
            if (referenceImages.isEmpty()) {
                raw = client.prompt().system(system).user(userContent).call().content();
            } else {
                raw = client.prompt().system(system).user(spec -> {
                    spec.text(userContent);
                    for (var image : referenceImages) {
                        spec.media(new Media(MimeTypeUtils.parseMimeType(image.mimeType()),
                                new ByteArrayResource(image.bytes())));
                    }
                }).call().content();
            }
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

    // ---------------------------------------------------------------- 结构化拆镜草稿（P0 制作视图）

    /** 一行结构化草稿：顺序即镜号建议；确认采纳时由服务端生成稳定行标识并入镜。 */
    public record StoryboardDraftRow(int orderNo, int durationTargetSeconds, String purpose,
                                     String action, String camera, String dialogue, String promptText) {
    }

    /**
     * 草稿生成结果：raw 始终保留原样（解析失败时供人工编辑）；
     * 解析失败不抛异常返回 PARSE_FAILED 语义，由调用方决定提示文案。
     */
    public record StoryboardDraftResult(boolean parsed, List<StoryboardDraftRow> rows,
                                        List<String> suggestions, List<String> missing,
                                        String raw, String modelName, String error) {
    }

    /**
     * 结构化拆镜（P0）：故事文本 → 可校验的镜头行草稿。与 optimize 同一套模型解析与上下文，
     * 但不做任何写入；确认采纳由 {@code StoryboardDraftService} 负责。
     */
    public StoryboardDraftResult storyboardDraft(CurrentActor actor, OptimizeRequest request) {
        String userId = actor.userId();
        String prompt = request == null ? null : request.prompt();
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("请输入要拆镜的故事内容");
        }
        String trimmed = prompt.trim();
        if (trimmed.length() > PROMPT_MAX) {
            throw CreativeException.unsupportedParameter("故事内容超过 " + PROMPT_MAX + " 个字符");
        }
        var context = buildContext(actor, MODE_VIDEO_STORYBOARD, request);
        var selection = catalogService.resolveWithFallback(
                userId, ModelCatalogService.PURPOSE_OPTIMIZE, ModelCatalogService.PURPOSE_CHAT);
        String userContent = trimmed + (context.isBlank() ? "" : "\n\n" + context);
        log.info("分镜草稿生成：project={} model={} provider={} storyChars={}",
                request.projectId(), selection.config().modelName(), selection.config().provider(), trimmed.length());
        String raw;
        try {
            ChatClient client = ChatClient.builder(chatModelFactory.model(selection.config())).build();
            raw = client.prompt().system(SYSTEM_VIDEO_STORYBOARD_DRAFT).user(userContent).call().content();
        } catch (Exception exception) {
            throw CreativeException.optimizeFailed("分镜草稿生成失败：" + rootMessage(exception));
        }
        try {
            var parsed = parseDraftRows(raw);
            return new StoryboardDraftResult(true, parsed.rows(), parsed.suggestions(), parsed.missing(),
                    raw, selection.config().modelName(), "");
        } catch (CreativeException exception) {
            // 解析失败：原故事未动，保留 raw 供人工编辑或重试
            return new StoryboardDraftResult(false, List.of(), List.of(), List.of(),
                    raw == null ? "" : raw, selection.config().modelName(), exception.getMessage());
        }
    }

    record ParsedDraft(List<StoryboardDraftRow> rows, List<String> suggestions, List<String> missing) {
    }

    static final int DRAFT_MAX_ROWS = 40;
    static final int DRAFT_MAX_SECONDS = 60;

    /** 草稿行解析：非法 JSON / 行越界 / 缺关键字段如实失败，不用纯文本兜底冒充结构。 */
    static ParsedDraft parseDraftRows(String raw) {
        if (raw == null || raw.isBlank()) {
            throw CreativeException.optimizeFailed("分镜格式未能解析，尚未修改已有镜头");
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw CreativeException.optimizeFailed("分镜格式未能解析，尚未修改已有镜头");
        }
        try {
            var node = CreativeJson.parse(text.substring(start, end + 1));
            var shots = node.path("shots");
            if (!shots.isArray() || shots.isEmpty()) {
                throw CreativeException.optimizeFailed("分镜草稿没有可用镜头行");
            }
            if (shots.size() > DRAFT_MAX_ROWS) {
                throw CreativeException.optimizeFailed("分镜草稿超过 " + DRAFT_MAX_ROWS + " 行上限，请分批拆镜");
            }
            List<StoryboardDraftRow> rows = new ArrayList<>();
            int index = 0;
            for (var shot : shots) {
                index++;
                int seconds = shot.path("durationTargetSeconds").asInt(0);
                if (seconds <= 0) seconds = 4;
                seconds = Math.max(1, Math.min(seconds, DRAFT_MAX_SECONDS));
                String action = shot.path("action").asText("").trim();
                String promptText = shot.path("promptText").asText("").trim();
                if (action.isBlank() && promptText.isBlank()) {
                    throw CreativeException.optimizeFailed("第 " + index + " 行缺少画面动作与生成提示词");
                }
                rows.add(new StoryboardDraftRow(
                        shot.path("orderNo").asInt(index),
                        seconds,
                        shot.path("purpose").asText("").trim(),
                        action,
                        shot.path("camera").asText("").trim(),
                        shot.path("dialogue").asText("").trim(),
                        promptText));
            }
            return new ParsedDraft(List.copyOf(rows), readList(node, "suggestions"), readList(node, "missing"));
        } catch (CreativeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw CreativeException.optimizeFailed("分镜格式未能解析，尚未修改已有镜头");
        }
    }

    // ---------------------------------------------------------------- 上下文组装（服务端按 ID 定向读取）

    /** 上下文信任边界：服务端按 ID 定向读取并校验归属，不信任客户端拼装的内容。 */
    private String buildContext(CurrentActor actor, String mode, OptimizeRequest request) {
        UUID projectId = request.projectId();
        if (projectId == null) return "";
        projectService.get(actor, projectId);
        var blocks = new StringBuilder();
        var settings = projectService.settingsOf(actor, projectId);
        // videoStyle/videoAspect/videoTargetSeconds 是内部设定键（画风为编码值），
        // 直接透传会让模型把编码抄进提示词；风格按媒介另行注入
        var displaySettings = new java.util.LinkedHashMap<String, Object>();
        settings.forEach((key, value) -> {
            if (!key.startsWith("video")) displaySettings.put(key, value);
        });
        appendBlock(blocks, "作品设定", displaySettings);
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

    // ---------------------------------------------------------------- 参考图（跟随原片）

    private record ReferenceImage(String mimeType, byte[] bytes) {
    }

    /**
     * 读取参考图用于风格分析：优先缩略图（体积小、时延低，风格判断不需要原图分辨率），
     * 无缩略图时回退原图；非图片、空文件、超限一律跳过，由调用方按「至少一张」判定。
     */
    private List<ReferenceImage> loadReferenceImages(CurrentActor actor, List<UUID> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) return List.of();
        var images = new ArrayList<ReferenceImage>();
        for (UUID assetId : assetIds.stream().filter(Objects::nonNull).distinct().toList()) {
            if (images.size() >= MAX_REFERENCE_IMAGES) break;
            byte[] thumbnail = assetService.thumbnail(actor, assetId);
            if (thumbnail != null && thumbnail.length > 0) {
                images.add(new ReferenceImage("image/png", thumbnail));
                continue;
            }
            var content = assetService.content(actor, assetId);
            String mimeType = content.mimeType() == null ? "" : content.mimeType().toLowerCase(Locale.ROOT);
            byte[] bytes = content.bytes();
            if (!mimeType.startsWith("image/")) continue;
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_REFERENCE_BYTES) continue;
            images.add(new ReferenceImage(content.mimeType(), bytes));
        }
        return images;
    }

    /**
     * 跟随原片必须有视觉能力：vision 用途优先，未绑定时回退 optimize/chat（多数对话模型支持图片输入）；
     * 都不可用才报错，并指向具体绑定位置，避免只抛「未配置模型」让人无从下手。
     */
    private ModelCatalogService.ResolvedSelection resolveVisionSelection(String userId) {
        try {
            return catalogService.resolve(userId, ModelCatalogService.PURPOSE_VISION);
        } catch (RuntimeException visionUnavailable) {
            try {
                return catalogService.resolveWithFallback(
                        userId, ModelCatalogService.PURPOSE_OPTIMIZE, ModelCatalogService.PURPOSE_CHAT);
            } catch (RuntimeException fallbackUnavailable) {
                throw CreativeException.optimizeFailed(
                        "「跟随原片」需要能读图的模型：请在设置→模型绑定中为 vision 用途选择模型");
            }
        }
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

    /** 图片风格归一化：缺省为自然写实；非法值显式拒绝，便于前端及时纠正。 */
    static String normalizeImageStyle(String imageStyle) {
        try {
            return ImageStyleCatalog.normalize(imageStyle);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("不支持的图片风格：" + imageStyle);
        }
    }

    /** 视频画风注记：未指定返回空串；非法编码照常显式拒绝。 */
    static String videoStyleNote(String imageStyle) {
        if (imageStyle == null || imageStyle.isBlank()) return "";
        return ImageStyleCatalog.videoNote(normalizeImageStyle(imageStyle));
    }

    static String imageSystemPrompt(String imageStyle) {
        String style = normalizeImageStyle(imageStyle);
        return switch (style) {
            case IMAGE_STYLE_COMIC -> SYSTEM_IMAGE_COMIC;
            case IMAGE_STYLE_PHOTO_MATCH -> SYSTEM_IMAGE_PHOTO_MATCH;
            case IMAGE_STYLE_REALISTIC -> SYSTEM_IMAGE_REALISTIC;
            default -> {
                ImageStyleCatalog.Style entry = ImageStyleCatalog.require(style);
                String template = switch (entry.group()) {
                    case ImageStyleCatalog.GROUP_CG -> SYSTEM_IMAGE_CG_TEMPLATE;
                    case ImageStyleCatalog.GROUP_ILLUSTRATION -> SYSTEM_IMAGE_ILLUSTRATION_TEMPLATE;
                    default -> SYSTEM_IMAGE_PHOTO_TEMPLATE;
                };
                yield template.formatted(entry.rubric());
            }
        };
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
