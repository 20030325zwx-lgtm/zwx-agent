package com.zwx.zwxagent.skills;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillPromptBuilderTest {
    private final BuiltInSkillRegistry registry = new BuiltInSkillRegistry(new ToolCallback[0]);
    private final SkillPromptBuilder promptBuilder = new SkillPromptBuilder(registry);

    @Test
    void doesNotExposeSkillsWhenWebSearchIsDisabled() {
        assertTrue(registry.availableFor("travel", false).isEmpty());
        assertEquals(0, registry.toolCallbacksFor("travel", false).length);
        assertTrue(promptBuilder.build("travel", false).contains("未授权任何外部 Skill"));
    }

    @Test
    void exposesWebResearchOnlyForAnAuthorizedTurn() {
        assertEquals("web-research", registry.availableFor("travel", true).getFirst().id());
        String prompt = promptBuilder.build("travel", true);
        assertTrue(prompt.contains("web-research"));
        assertTrue(prompt.contains("触发条件"));
        assertTrue(prompt.contains("工具失败"));
        assertFalse(prompt.contains("未授权任何外部 Skill"));
    }
}
