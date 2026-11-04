package com.zwx.zwxagent.creative.skill;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 技能方案提示词压缩：超过模型上限时必须落到可提交长度，并留下 warnings。
 */
class CreativeSkillPromptClipTest {

    @Test
    void shortPromptIsUnchanged() {
        List<String> warnings = new ArrayList<>();
        Assertions.assertEquals("一段短提示词",
                CreativeSkillService.clipPrompt("  一段短提示词  ", "方案", warnings));
        Assertions.assertTrue(warnings.isEmpty());
    }

    @Test
    void clipsAtSentenceBoundaryBeforeLimit() {
        String text = "甲".repeat(480) + "。" + "乙".repeat(100);
        List<String> warnings = new ArrayList<>();
        String clipped = CreativeSkillService.clipPrompt(text, "第 1 镜", warnings);
        Assertions.assertEquals(481, clipped.length());
        Assertions.assertEquals(1, warnings.size());
        Assertions.assertTrue(warnings.get(0).contains("超过 500 字"));
        Assertions.assertTrue(warnings.get(0).contains("第 1 镜"));
    }

    @Test
    void hardCutsWhenNoSentenceBoundaryExists() {
        List<String> warnings = new ArrayList<>();
        String clipped = CreativeSkillService.clipPrompt("甲".repeat(700), "方案", warnings);
        Assertions.assertEquals(500, clipped.length());
        Assertions.assertEquals(1, warnings.size());
    }

    @Test
    void nullBecomesEmptyWithoutWarning() {
        List<String> warnings = new ArrayList<>();
        Assertions.assertEquals("", CreativeSkillService.clipPrompt(null, "方案", warnings));
        Assertions.assertTrue(warnings.isEmpty());
    }
}
