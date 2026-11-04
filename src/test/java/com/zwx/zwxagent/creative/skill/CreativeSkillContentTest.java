package com.zwx.zwxagent.creative.skill;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 内置技能种子契约：slug 唯一；视频提示词优化师携带共享七要素规则，
 * 并输出分镜拆解（shots）与负向提示（negativePrompt）。该技能仍只有 CHAT 能力，
 * 前端必须按 mediaOperation 判断是否允许逐镜生成，而不是看到 shots 就提交任务。
 */
class CreativeSkillContentTest {

    @Test
    void optimizerSeedCarriesSharedRubricAndStoryboardContract() {
        CreativeSkillContent.Seed optimizer = CreativeSkillContent.SEEDS.stream()
                .filter(seed -> "video-prompt-optimizer".equals(seed.slug()))
                .findFirst()
                .orElseThrow();

        Assertions.assertEquals("视频提示词优化师", optimizer.name());
        Assertions.assertEquals("DIRECTING", optimizer.category());
        Assertions.assertEquals("PROMPT", optimizer.stage());
        Assertions.assertEquals(List.of("CHAT"), optimizer.requiredCapabilities());
        Assertions.assertTrue(optimizer.body().contains(
                "[主体] + [动作] + [环境] + [镜头语言] + [光线色调] + [风格] + [质感]"));
        Assertions.assertTrue(optimizer.body().contains("Close-up 特写"));
        Assertions.assertTrue(optimizer.body().contains("Dolly in 推轨"));
        Assertions.assertTrue(optimizer.body().contains("anatomically correct hands and face"));
        Assertions.assertTrue(optimizer.body().contains("\"shots\""));
        Assertions.assertTrue(optimizer.body().contains("\"negativePrompt\""));
        Assertions.assertTrue(optimizer.body().contains("分镜拆解"));
    }

    @Test
    void seedSlugsAreUnique() {
        Set<String> slugs = new HashSet<>();
        for (CreativeSkillContent.Seed seed : CreativeSkillContent.SEEDS) {
            Assertions.assertTrue(slugs.add(seed.slug()), "重复的技能 slug：" + seed.slug());
        }
    }

    @Test
    void retiredSkillsAreNotReseeded() {
        Set<String> slugs = new HashSet<>();
        for (CreativeSkillContent.Seed seed : CreativeSkillContent.SEEDS) {
            slugs.add(seed.slug());
        }
        // 2026-11-04 清理：重叠/小众技能由 V27 下架，种子不得再重建
        Assertions.assertFalse(slugs.contains("camera-performance-designer"));
        Assertions.assertFalse(slugs.contains("ambient-motion"));
        Assertions.assertFalse(slugs.contains("fashion-look-display"));
    }
}
