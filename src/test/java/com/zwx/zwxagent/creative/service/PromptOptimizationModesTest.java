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
    void catalogExposesPhotographyLooksAndSingleReferenceStyle() {
        var views = ImageStyleCatalog.views();
        Assertions.assertEquals(views.size(),
                views.stream().map(ImageStyleCatalog.View::code).distinct().count(), "风格编码不能重复");
        Assertions.assertEquals(List.of(
                        "PHOTO_MATCH", "REALISTIC", "FILM_PORTRAIT", "BLACK_WHITE", "DOCUMENTARY", "CINEMATIC",
                        "LANDSCAPE", "PRODUCT", "RETRO_CCD", "JAPANESE_FRESH", "MOODY_NIGHT", "MACRO", "COMIC"),
                views.stream().map(ImageStyleCatalog.View::code).toList());
        Assertions.assertEquals(List.of("PHOTO_MATCH"),
                views.stream().filter(ImageStyleCatalog.View::needsReference)
                        .map(ImageStyleCatalog.View::code).toList(),
                "只有跟随原片依赖参考图");
        Assertions.assertTrue(views.stream().allMatch(view ->
                !view.label().isBlank() && !view.hint().isBlank() && !view.group().isBlank()));
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
                    Assertions.assertTrue(prompt.contains("【风格锚点】"), style.code() + " 未使用摄影骨架");
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
