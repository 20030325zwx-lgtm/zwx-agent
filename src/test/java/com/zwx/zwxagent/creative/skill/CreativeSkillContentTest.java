package com.zwx.zwxagent.creative.skill;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 内置技能种子契约：slug 唯一；视频提示词优化师携带共享七要素规则，
 * 且明确禁止输出 shots（否则前端会误触发逐镜确认，而该技能只有 CHAT 能力）。
 */
class CreativeSkillContentTest {

    @Test
    void optimizerSeedCarriesSharedRubricAndSingleShotContract() {
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
        Assertions.assertTrue(optimizer.body().contains("不要输出 shots"));
        Assertions.assertTrue(optimizer.body().contains("\"changes\""));
    }

    @Test
    void seedSlugsAreUnique() {
        Set<String> slugs = new HashSet<>();
        for (CreativeSkillContent.Seed seed : CreativeSkillContent.SEEDS) {
            Assertions.assertTrue(slugs.add(seed.slug()), "重复的技能 slug：" + seed.slug());
        }
    }
}
