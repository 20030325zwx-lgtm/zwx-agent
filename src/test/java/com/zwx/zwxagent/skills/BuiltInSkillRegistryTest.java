package com.zwx.zwxagent.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInSkillRegistryTest {

    @TempDir
    Path tempDir;

    private static ToolCallback callback(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
    }

    private static final String WEB_SKILL = """
            ---
            id: web-research
            name: 联网查询
            agents: [love, travel]
            tools: [webSearch]
            trigger: 时效性事实
            ---
            先检索再回答。
            """;

    private static final String PROMPT_ONLY_SKILL = """
            ---
            id: love-advisor
            name: 恋爱建议
            agents: [love]
            tools: []
            trigger: 用户讨论情感话题
            ---
            用温和的语气给出建议。
            """;

    private BuiltInSkillRegistry registryWithFiles(ToolCallback[] webTools) throws IOException {
        Files.writeString(tempDir.resolve("01-web.md"), WEB_SKILL);
        Files.writeString(tempDir.resolve("02-prompt.md"), PROMPT_ONLY_SKILL);
        SkillRepository repository = new SkillRepository(tempDir);
        repository.reload();
        return new BuiltInSkillRegistry(webTools, null, repository);
    }

    @Test
    void promptOnlySkillIsAvailableWithoutWebSearchFlag() throws IOException {
        BuiltInSkillRegistry registry = registryWithFiles(new ToolCallback[0]);
        List<String> available = registry.availableFor("love", false).stream().map(BuiltInSkill::id).toList();
        assertEquals(List.of("love-advisor"), available);
        assertEquals(0, registry.toolCallbacksFor("love", false).length);
    }

    @Test
    void webSearchSkillRequiresTurnFlag() throws IOException {
        ToolCallback[] webTools = new ToolCallback[]{callback("webSearch")};
        BuiltInSkillRegistry registry = registryWithFiles(webTools);
        assertTrue(registry.availableFor("love", false).stream().noneMatch(skill -> "web-research".equals(skill.id())));
        assertEquals(0, registry.toolCallbacksFor("love", false).length);
        assertTrue(registry.availableFor("love", true).stream().anyMatch(skill -> "web-research".equals(skill.id())));
        assertEquals(1, registry.toolCallbacksFor("love", true).length);
    }

    @Test
    void promptIsBuiltFromMarkdownInstruction() throws IOException {
        BuiltInSkillRegistry registry = registryWithFiles(new ToolCallback[0]);
        String prompt = new SkillPromptBuilder(registry).build("love", false);
        assertTrue(prompt.contains("love-advisor"));
        assertTrue(prompt.contains("用温和的语气给出建议"));
    }

    @Test
    void catalogExposesSourceField() throws IOException {
        BuiltInSkillRegistry registry = registryWithFiles(new ToolCallback[0]);
        List<SkillCatalogItem> catalog = registry.catalogWithConfiguration("default", "love");
        assertEquals(2, catalog.size());
        assertTrue(catalog.stream().allMatch(item -> SkillRepository.SOURCE_MARKDOWN.equals(item.source())));
    }

    @Test
    void emptyRepositoryFallsBackToBuiltinWebResearch() {
        BuiltInSkillRegistry registry = new BuiltInSkillRegistry(new ToolCallback[0]);
        assertEquals("web-research", registry.availableFor("travel", true).getFirst().id());
        assertEquals(SkillRepository.SOURCE_BUILTIN, registry.catalogWithConfiguration("default", "travel").getFirst().source());
    }

    @Test
    void saveConfigurationRejectsUnknownSkills() throws IOException {
        Files.writeString(tempDir.resolve("01-web.md"), WEB_SKILL);
        SkillConfigurationService configurationService = new SkillConfigurationService(new JdbcTemplate());
        SkillRepository repository = new SkillRepository(tempDir);
        repository.reload();
        BuiltInSkillRegistry registry = new BuiltInSkillRegistry(new ToolCallback[0], configurationService, repository);
        IllegalArgumentException cause = assertThrows(IllegalArgumentException.class,
                () -> registry.saveConfiguration("default", "love", Set.of("not-a-skill")));
        assertTrue(cause.getMessage().contains("Unknown Skill"));
    }
}
