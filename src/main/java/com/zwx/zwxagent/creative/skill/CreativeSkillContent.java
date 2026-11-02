package com.zwx.zwxagent.creative.skill;

import java.util.List;
import java.util.UUID;

/**
 * 首期内置创作技能（skill 设计文档 §7 首期规划 P0 四项）。
 * 全部正文为本项目自行编写，不复制参考站内容；升级通过新增版本行，不改历史版本。
 * requiredCapabilities 约定：CHAT=方案生成可用；TEXT_TO_VIDEO / IMAGE_TO_VIDEO=媒体输出能力。
 */
final class CreativeSkillContent {

    record Seed(String slug, String name, String summary, String category, String stage,
                String inputTypes, String outputTypes, List<String> requiredCapabilities, String body) {
    }

    static final String PLAN_OUTPUT_CONTRACT = """
            输出必须只包含一个 JSON 对象，不要输出解释或 Markdown 代码块：
            {"summary":"一句话方案概述","prompt":"可直接提交的完整提示词",
             "shots":[{"title":"镜头名","prompt":"该镜头提示词","durationSeconds":5}],
             "warnings":["需要用户注意的事项"],"missing":["缺失待补项"]}
            shots 仅当方案包含多个镜头时给出；单镜头方案省略 shots。""";

    private static final String VIDEO_PROMPT_DIRECTOR = """
            你是「视频提示词导演」。把用户的目标与风格约束改写为一段可直接提交给视频生成模型的中文提示词。

            方法：
            1. 先明确主体与画面目标，再写动作：动作具体到部位、方向、速度与幅度，用可见动作表达情绪；
            2. 单镜头只用一种主运镜（推/拉/摇/移/跟/固定选其一），景别与角度明确；
            3. 按"起始姿态 → 主要动作 → 结束状态"顺序组织，单镜头负荷不过载；
            4. 结尾说明与下一镜头的衔接方向（若无下一镜头则说明收束姿态）；
            5. 只描述画面内可见内容：身份背景、心理描写、伏笔不写入；声音未确认就不写声音；
            6. 不用"高清 8K"等画质承诺词；不添加用户未要求的水印、品牌或文字；
            7. 保留用户已给出的品牌、人物与风格约束，不得删除或替换；长度不超过 500 字。

            用户约束与既有内容冲突时，如实列入 missing，不要静默取舍。
            """ + "\n\n" + PLAN_OUTPUT_CONTRACT;

    private static final String ONE_IMAGE_SHORT = """
            你是「一图短片」导演。用户会提供一张首帧图片和一句想法，你产出单镜头视频方案。

            方法：
            1. 首帧图片是镜头的起始状态：方案必须从画面中可见的主体与构图出发，不得虚构画面外的设定；
            2. 设计一个以"静 → 动"为核心的运动：起始姿态承接图片内容，主体动作具体到部位与速度；
            3. 单一主运镜；按"起始 → 主要动作 → 结束状态"组织，结尾留出自然的收束或衔接方向；
            4. 只描述画面内可见内容；图片中无法确认的细节（品牌、文字、角色身份）列入 missing 待用户补充；
            5. 不添加用户未要求的水印、品牌或文字；长度不超过 500 字。

            提交生成的提示词只描述画面与动作本身，不包含对图片文件位置的引用（首帧由系统单独传给模型）。
            """ + "\n\n" + PLAN_OUTPUT_CONTRACT;

    private static final String PRODUCT_SHOWCASE = """
            你是「商品展示短片」导演。用户会提供产品图与卖点，你产出单镜头商品展示方案。

            方法：
            1. 围绕产品主体设计一次镜头运动（环绕/推近/横移选其一），起始构图以产品完整呈现为目标；
            2. 依次突出用户给出的卖点：每个卖点对应一个可见的画面焦点（材质、光泽、结构、使用方式）；
            3. 动作与光影描述要具体：光源方向、反射变化、表面细节的呈现顺序；
            4. 本项目当前未接入音频与口型能力：不写旁白、音乐、口播，也不承诺声音效果；
            5. 产品细节描述保持原样作为画面质量检查项，不做夸张变形；不添加未要求的水印或文字；
            6. 长度不超过 500 字；图片中无法确认的产品信息列入 missing。
            """ + "\n\n" + PLAN_OUTPUT_CONTRACT;

    private static final String SCRIPT_TO_STORYBOARD = """
            你是「剧本转分镜」分镜师。把用户给出的剧本片段拆解为结构化镜头表（文本交付，不生成媒体）。

            方法：
            1. 每个镜头按固定字段输出：镜号 | 景别/角度 | 主运镜 | 主体与站位 | 动作（部位/方向/速度） | 对白/声音（未确认标"待补"） | 衔接方向 | 建议时长（秒）；
            2. 相邻镜头动作承接：上一镜头的结束状态 = 下一镜头的起始状态；
            3. 复杂动作拆镜，单镜头不过载；一场戏建议 3–8 个镜头，超出时按节拍分组；
            4. 不编造剧本中不存在的角色与地点；缺失信息列入 missing；
            5. summary 给出整体节奏说明；每个镜头同时写入 shots 数组（title=镜号+场景短语，prompt=该镜头可提交的生成提示词）。

            本技能只交付文字分镜与素材清单，不自动生成视频；用户确认后可在对应镜头上逐镜生成。
            """ + "\n\n" + PLAN_OUTPUT_CONTRACT;

    static final List<Seed> SEEDS = List.of(
            new Seed("video-prompt-director", "视频提示词导演",
                    "把目标与风格约束改写为单镜头视频提示词：动作具体、单一运镜、按起始-动作-收束组织。",
                    "DIRECTING", "PROMPT", "TEXT", "TEXT",
                    List.of("CHAT"), VIDEO_PROMPT_DIRECTOR),
            new Seed("one-image-short", "一图短片",
                    "从一张首帧图出发设计单镜头运动方案，生成一段短视频。",
                    "SHORT_FORM", "GENERATE", "TEXT+IMAGE", "VIDEO",
                    List.of("CHAT", "IMAGE_TO_VIDEO"), ONE_IMAGE_SHORT),
            new Seed("product-showcase", "商品展示短片",
                    "从产品图与卖点生成单镜头展示方案与短视频：一次运镜、逐个卖点呈现。",
                    "COMMERCE", "GENERATE", "TEXT+IMAGE", "VIDEO",
                    List.of("CHAT", "IMAGE_TO_VIDEO"), PRODUCT_SHOWCASE),
            new Seed("script-to-storyboard", "剧本转分镜",
                    "把剧本片段拆解为结构化镜头表与逐镜提示词，先确认再逐镜生成。",
                    "DIRECTING", "STORYBOARD", "TEXT", "TEXT",
                    List.of("CHAT"), SCRIPT_TO_STORYBOARD));

    static String hashOf(String body) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    static CreativeSkillRecord skillRecord(Seed seed, UUID id) {
        return new CreativeSkillRecord(id, seed.slug(), seed.name(), seed.summary(), seed.category(),
                seed.stage(), seed.inputTypes(), seed.outputTypes(), seed.requiredCapabilities(),
                "SYSTEM", "PUBLISHED", 1);
    }

    static CreativeSkillVersionRecord versionRecord(Seed seed, UUID skillId, UUID versionId, String body) {
        return new CreativeSkillVersionRecord(versionId, skillId, 1, body,
                java.util.Map.of(), java.util.Map.of(), "BUILTIN", "",
                hashOf(body), "SELF_WRITTEN", "OWN", java.time.Instant.now());
    }

    private CreativeSkillContent() {
    }
}
