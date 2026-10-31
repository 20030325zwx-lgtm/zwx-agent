package com.zwx.zwxagent.creative.novel;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** AI 小说 v1 纯规则：字数统计、剧情建议 JSON 解析容错、设定文本块拼装。 */
class NovelRulesTest {

    @Test
    void wordCountIgnoresWhitespace() {
        Assertions.assertEquals(0, NovelChapterService.wordCount(null));
        Assertions.assertEquals(0, NovelChapterService.wordCount("  \n\t "));
        Assertions.assertEquals(6, NovelChapterService.wordCount("星坠前夜正文"));
        Assertions.assertEquals(8, NovelChapterService.wordCount("星坠 前夜\n正文两段"));
    }

    @Test
    void parseSuggestionsAcceptsJsonArrayAndCodeFence() {
        String raw = """
                ```json
                [{"title":"星坠前夜","direction":"沈观澜观测到辅星逆轨。"},
                 {"title":"断锚之港","direction":"顾长风潜入官仓盗云鲸膏。"},
                 {"title":"双轨初隙","direction":"二人各自发现旧案线索。"}]
                ```
                """;
        List<NovelPlanningService.PlotSuggestion> items = NovelPlanningService.parseSuggestions(raw);
        Assertions.assertEquals(3, items.size());
        Assertions.assertEquals("星坠前夜", items.getFirst().title());
        Assertions.assertEquals("沈观澜观测到辅星逆轨。", items.getFirst().direction());
    }

    @Test
    void parseSuggestionsToleratesGarbageAndSkipsIncompleteEntries() {
        Assertions.assertTrue(NovelPlanningService.parseSuggestions(null).isEmpty());
        Assertions.assertTrue(NovelPlanningService.parseSuggestions("模型胡言乱语，没有数组").isEmpty());
        Assertions.assertEquals(1, NovelPlanningService.parseSuggestions(
                "[{\"title\":\"只有标题\",\"direction\":\"\"},{\"title\":\"完整\",\"direction\":\"有效\"}]").size(),
                "缺 direction 的条目应跳过");
        Assertions.assertEquals(3, NovelPlanningService.parseSuggestions(
                "[{\"title\":\"a\",\"direction\":\"1\"},{\"title\":\"b\",\"direction\":\"2\"}," +
                        "{\"title\":\"c\",\"direction\":\"3\"},{\"title\":\"d\",\"direction\":\"4\"}]").size(),
                "最多返回 3 组候选");
    }

    @Test
    void settingsBlockRendersKnownKeysOnly() {
        Assertions.assertEquals("（暂无设定）", NovelPlanningService.settingsBlock(Map.of()));
        String block = NovelPlanningService.settingsBlock(Map.of(
                "characters", "沈观澜：星槎司观测官",
                "worldview", "海上浮空岛星洲六十年一降",
                "style", "第三人称，画面感强",
                "ignored", "不应出现"));
        Assertions.assertTrue(block.contains("人物：沈观澜"));
        Assertions.assertTrue(block.contains("世界观：海上浮空岛"));
        Assertions.assertTrue(block.contains("文风：第三人称"));
        Assertions.assertFalse(block.contains("不应出现"));
    }
}
