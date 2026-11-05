package com.zwx.zwxagent.creative.service;

import com.zwx.zwxagent.creative.domain.CreativeException;

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
}
