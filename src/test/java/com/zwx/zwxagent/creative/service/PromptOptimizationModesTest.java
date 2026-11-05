package com.zwx.zwxagent.creative.service;

import com.zwx.zwxagent.creative.domain.CreativeException;
import com.zwx.zwxagent.creative.support.ImageStyleCatalog;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 提示词优化多模式解析（P0）：IMAGE 兼容旧 JSON/纯文本兜底；
 * 视频模式结构化输出必须合法——非法 JSON/缺提交文本如实失败，不用纯文本兜底冒充结构。
 */
class PromptOptimizationModesTest {

    @Test
    void imageModeLegacyParsingStillWorks() {
        Assertions.assertEquals("改写结果",
                PromptOptimizationService.extractPrompt("{\"prompt\":\"改写结果\"}"));
        Assertions.assertEquals("```包裹".replace("```", ""),
                PromptOptimizationService.extractPrompt("```json\n{\"prompt\":\"包裹\"}\n```"));
        Assertions.assertEquals("纯文本结果",
                PromptOptimizationService.extractPrompt("纯文本结果"));
    }

    @Test
    void structuredParsingAcceptsFullJson() {
        var result = PromptOptimizationService.extractStructured("""
                {"optimizedText":"雨夜的旧车站入口……","changes":["补充了动作顺序"],
                 "suggestions":["可拆两镜"],"missing":["服装未确认"]}
                """);
        Assertions.assertEquals("雨夜的旧车站入口……", result.optimizedText());
        Assertions.assertEquals(List.of("补充了动作顺序"), result.changes());
        Assertions.assertEquals(List.of("可拆两镜"), result.suggestions());
        Assertions.assertEquals(List.of("服装未确认"), result.missing());
    }

    @Test
    void structuredParsingToleratesFencesAndNoise() {
        var result = PromptOptimizationService.extractStructured(
                "好的，以下是结果：\n```json\n{\"optimizedText\":\"提交文本\"}\n```");
        Assertions.assertEquals("提交文本", result.optimizedText());
        Assertions.assertTrue(result.changes().isEmpty());
    }

    @Test
    void structuredParsingRejectsPlainTextInput() {
        // 视频模式不做纯文本兜底：结构不合法时如实失败（规划 §7.3）
        Assertions.assertThrows(CreativeException.class,
                () -> PromptOptimizationService.extractStructured("这就是优化结果，没有 JSON 结构"));
        Assertions.assertThrows(CreativeException.class,
                () -> PromptOptimizationService.extractStructured(null));
    }

    @Test
    void structuredParsingRejectsMissingOptimizedText() {
        Assertions.assertThrows(CreativeException.class,
                () -> PromptOptimizationService.extractStructured("{\"changes\":[\"只有建议\"]}"));
    }

    // ---- 图片风格（写实/漫画）——两套规范各自独立，缺省写实，非法值显式拒绝 ----

    @Test
    void imageStyleDefaultsToRealisticAndAcceptsComic() {
        Assertions.assertEquals(PromptOptimizationService.IMAGE_STYLE_REALISTIC,
                PromptOptimizationService.normalizeImageStyle(null));
        Assertions.assertEquals(PromptOptimizationService.IMAGE_STYLE_REALISTIC,
                PromptOptimizationService.normalizeImageStyle("  "));
        Assertions.assertEquals(PromptOptimizationService.IMAGE_STYLE_COMIC,
                PromptOptimizationService.normalizeImageStyle("comic"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> PromptOptimizationService.normalizeImageStyle("anime"));
    }

    @Test
    void styleSpecificSystemPromptsCarryTheirRubrics() {
        String realistic = PromptOptimizationService.imageSystemPrompt(
                PromptOptimizationService.IMAGE_STYLE_REALISTIC);
        Assertions.assertTrue(realistic.contains("写实照片"));
        Assertions.assertTrue(realistic.contains("等效焦段"));
        Assertions.assertTrue(realistic.contains("皮肤质感"));
        Assertions.assertTrue(realistic.contains("只输出 JSON"));

        String comic = PromptOptimizationService.imageSystemPrompt(
                PromptOptimizationService.IMAGE_STYLE_COMIC);
        Assertions.assertTrue(comic.contains("漫画风插画"));
        Assertions.assertTrue(comic.contains("墨线描边"));
        Assertions.assertTrue(comic.contains("赛璐璐"));
        Assertions.assertTrue(comic.contains("网点"));
        Assertions.assertFalse(comic.contains("f/1.8"), "漫画规范不应混入写实镜头参数");
    }

    // ---- 摄影风格目录（2026-11-05）：每种取向一份风格锚点，跟随原片走视觉输入 ----

    @Test
    void catalogExposesStylesGroupedByMediumWithMetadata() {
        var views = ImageStyleCatalog.views();
        Assertions.assertEquals(views.size(),
                views.stream().map(ImageStyleCatalog.View::code).distinct().count(), "风格编码不能重复");
        Assertions.assertEquals(30, views.size(), "风格目录规模：摄影 18 + 3D 4 + 插画 8");
        Assertions.assertEquals(List.of("PHOTO_MATCH"),
                views.stream().filter(ImageStyleCatalog.View::needsReference)
                        .map(ImageStyleCatalog.View::code).toList(),
                "只有跟随原片依赖参考图");
        Assertions.assertTrue(views.stream().allMatch(view ->
                !view.label().isBlank() && !view.hint().isBlank() && !view.group().isBlank()
                        && !view.category().isBlank() && !view.tags().isEmpty()
                        && view.tags().stream().noneMatch(String::isBlank)
                        && view.accent().size() == 2));

        Assertions.assertEquals(18, views.stream().filter(view -> ImageStyleCatalog.GROUP_PHOTO.equals(view.group())).count());
        Assertions.assertEquals(4, views.stream().filter(view -> ImageStyleCatalog.GROUP_CG.equals(view.group())).count());
        Assertions.assertEquals(8, views.stream().filter(view -> ImageStyleCatalog.GROUP_ILLUSTRATION.equals(view.group())).count());

        // 已保存的画布节点里可能存着旧编码，必须继续可用
        List<String> legacyCodes = List.of("PHOTO_MATCH", "REALISTIC", "FILM_PORTRAIT", "BLACK_WHITE",
                "DOCUMENTARY", "CINEMATIC", "LANDSCAPE", "PRODUCT", "RETRO_CCD", "JAPANESE_FRESH",
                "MOODY_NIGHT", "MACRO", "COMIC");
        List<String> codes = views.stream().map(ImageStyleCatalog.View::code).toList();
        Assertions.assertTrue(codes.containsAll(legacyCodes), "旧风格编码不能删除");

        // 预览图与目录一一对应：缺图会让风格中心出现空卡片
        var previewDir = java.nio.file.Path.of("frontend/public/style-previews");
        Assertions.assertTrue(java.nio.file.Files.isDirectory(previewDir), "缺少风格预览图目录");
        for (var view : views) {
            Assertions.assertEquals("/style-previews/" + view.code() + ".jpg", view.cover());
            Assertions.assertTrue(java.nio.file.Files.isRegularFile(previewDir.resolve(view.code() + ".jpg")),
                    view.code() + " 缺少预览图");
        }
    }

    @Test
    void everyPhotographyStyleInjectsItsOwnAnchor() {
        for (var style : ImageStyleCatalog.styles()) {
            String prompt = PromptOptimizationService.imageSystemPrompt(style.code());
            Assertions.assertTrue(prompt.contains("只输出 JSON"), style.code() + " 缺少输出契约");
            switch (style.code()) {
                case "COMIC", "PHOTO_MATCH", "REALISTIC" -> {
                    // 这三种走各自独立系统提示词，不使用通用骨架
                }
                default -> {
                    Assertions.assertFalse(style.rubric().isBlank(), style.code() + " 缺少风格锚点");
                    Assertions.assertTrue(prompt.contains(style.rubric()), style.code() + " 的锚点未注入");
                    Assertions.assertTrue(prompt.contains("【风格锚点】"), style.code() + " 未使用骨架模板");
                }
            }
        }

        String film = PromptOptimizationService.imageSystemPrompt("FILM_PORTRAIT");
        Assertions.assertTrue(film.contains("Portra 400"));
        Assertions.assertTrue(film.contains("85mm"));
        Assertions.assertTrue(PromptOptimizationService.imageSystemPrompt("CINEMATIC").contains("宽银幕"));

        String match = PromptOptimizationService.imageSystemPrompt(
                PromptOptimizationService.IMAGE_STYLE_PHOTO_MATCH);
        Assertions.assertTrue(match.contains("风格要素"));
        Assertions.assertTrue(match.contains("不复述参考片"));
        Assertions.assertTrue(match.contains("只输出 JSON"));
        Assertions.assertFalse(match.contains("Portra 400"), "跟随原片不应预置某个具体胶片取向");
    }

    // ---- 媒介骨架（2026-11-05）：摄影/3D/2D 各走一套规范化流程，风格差异只在锚点 ----

    @Test
    void eachMediumUsesItsOwnSkeleton() {
        String photo = PromptOptimizationService.imageSystemPrompt("FILM_PORTRAIT");
        Assertions.assertTrue(photo.contains("摄影方向"), "真实拍摄走摄影骨架");
        Assertions.assertTrue(photo.contains("等效焦段"));

        String cg = PromptOptimizationService.imageSystemPrompt("PIXAR_3D");
        Assertions.assertTrue(cg.contains("3D 渲染方向"), "3D 风格走渲染骨架");
        Assertions.assertTrue(cg.contains("次表面散射"));
        Assertions.assertFalse(cg.contains("皮肤质感（毛孔"), "3D 骨架不应套用写实皮肤要求");

        String illustration = PromptOptimizationService.imageSystemPrompt("WATERCOLOR");
        Assertions.assertTrue(illustration.contains("2D 插画"), "插画风格走 2D 骨架");
        Assertions.assertTrue(illustration.contains("湿画法晕染"));

        Assertions.assertTrue(PromptOptimizationService.imageSystemPrompt("NOIR_THRILLER").contains("冷青绿"));
        Assertions.assertTrue(PromptOptimizationService.imageSystemPrompt("GUOMAN_2D").contains("岩彩"));
        Assertions.assertTrue(PromptOptimizationService.imageSystemPrompt("CLAY_STOPMOTION").contains("指纹"));
    }

    // ---- 视频画风（2026-11-05）：同一份风格目录复用到视频提示词，未选则不注入 ----

    @Test
    void videoStyleNoteIsCompactAndOptional() {
        Assertions.assertEquals("", PromptOptimizationService.videoStyleNote(null));
        Assertions.assertEquals("", PromptOptimizationService.videoStyleNote("   "));
        String note = PromptOptimizationService.videoStyleNote("cinematic");
        Assertions.assertTrue(note.startsWith("电影感："));
        Assertions.assertTrue(note.contains("宽银幕"));
        Assertions.assertTrue(PromptOptimizationService.videoStyleNote("PHOTO_MATCH").contains("首帧"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> PromptOptimizationService.videoStyleNote("NOT_A_STYLE"));
    }

    // ---- 结构化拆镜草稿（P0 制作视图）：可校验行集合，解析失败如实报错 ----

    @Test
    void storyboardDraftRowsParseAndClamp() {
        var parsed = PromptOptimizationService.parseDraftRows("""
                {"shots":[
                  {"orderNo":1,"durationTargetSeconds":5,"purpose":"开场","action":"角色入画",
                   "camera":"中景","dialogue":"","promptText":"雨天石桥"},
                  {"durationTargetSeconds":999,"action":"","camera":"","dialogue":"","promptText":"背影远去"}
                 ],
                 "suggestions":["可拆两镜"],"missing":["服装未确认"]}
                """);
        Assertions.assertEquals(2, parsed.rows().size());
        Assertions.assertEquals(5, parsed.rows().get(0).durationTargetSeconds());
        Assertions.assertEquals(60, parsed.rows().get(1).durationTargetSeconds(), "时长必须收敛到模型上限");
        Assertions.assertEquals("背影远去", parsed.rows().get(1).promptText());
        Assertions.assertEquals(List.of("可拆两镜"), parsed.suggestions());
    }

    @Test
    void storyboardDraftParseFailuresAreHonest() {
        // 无镜头行
        Assertions.assertThrows(com.zwx.zwxagent.creative.domain.CreativeException.class,
                () -> PromptOptimizationService.parseDraftRows("{\"shots\":[]}"));
        // 完全没有动作与提示词的行
        Assertions.assertThrows(com.zwx.zwxagent.creative.domain.CreativeException.class,
                () -> PromptOptimizationService.parseDraftRows(
                        "{\"shots\":[{\"durationTargetSeconds\":4,\"purpose\":\"空行\"}]}"));
        // 非 JSON 输出
        Assertions.assertThrows(com.zwx.zwxagent.creative.domain.CreativeException.class,
                () -> PromptOptimizationService.parseDraftRows("以下是分镜草稿……"));
    }

    @Test
    void newStyleCodesNormalizeAndUnknownStillRejected() {
        Assertions.assertEquals("MACRO", PromptOptimizationService.normalizeImageStyle(" macro "));
        Assertions.assertEquals("PHOTO_MATCH", PromptOptimizationService.normalizeImageStyle("photo_match"));
        Assertions.assertEquals("REALISTIC", PromptOptimizationService.normalizeImageStyle(""));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> PromptOptimizationService.normalizeImageStyle("oil-painting"));
        Assertions.assertTrue(ImageStyleCatalog.needsReference("PHOTO_MATCH"));
        Assertions.assertFalse(ImageStyleCatalog.needsReference("CINEMATIC"));
        Assertions.assertTrue(ImageStyleCatalog.views().stream().noneMatch(view -> view.hint().length() > 60),
                "提示语要短，避免撑开检查器");
    }
}
