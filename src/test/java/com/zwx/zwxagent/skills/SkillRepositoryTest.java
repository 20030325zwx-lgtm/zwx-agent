package com.zwx.zwxagent.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRepositoryTest {

    @TempDir
    Path tempDir;

    private static final String FULL_SKILL = """
            ---
            id: web-research
            name: 联网查询
            agents: [love, travel, test]
            tools: [webSearch]
            trigger: 用户询问时效性事实
            description: 检索可能变化的公开信息。
            ---
            1. 先检索再回答。
            2. 证据不足时明确说明。
            """;

    private void write(String fileName, String content) throws IOException {
        Files.writeString(tempDir.resolve(fileName), content);
    }

    @Test
    void parsesFrontMatterArraysAndBody() {
        BuiltInSkill skill = SkillRepository.parse("web-research.md", FULL_SKILL);
        assertEquals("web-research", skill.id());
        assertEquals("联网查询", skill.name());
        assertEquals("检索可能变化的公开信息。", skill.description());
        assertEquals("用户询问时效性事实", skill.trigger());
        assertEquals(List.of("love", "test", "travel"), skill.agentKeys().stream().sorted().toList());
        assertEquals(List.of("webSearch"), skill.tools());
        assertTrue(skill.instruction().contains("先检索再回答"));
        assertEquals(SkillRepository.SOURCE_MARKDOWN, skill.source());
    }

    @Test
    void stripsQuotesAndToleratesBlankArrayItems() {
        String content = """
                ---
                id: "note-taker"
                name: '笔记'
                agents: [ love , ]
                tools: []
                trigger: 用户要求记录笔记
                ---
                正文
                """;
        BuiltInSkill skill = SkillRepository.parse("note-taker.md", content);
        assertEquals("note-taker", skill.id());
        assertEquals("笔记", skill.name());
        assertEquals(java.util.Set.of("love"), skill.agentKeys());
        assertTrue(skill.tools().isEmpty());
    }

    @Test
    void descriptionDefaultsToBodyFirstLine() {
        String content = """
                ---
                id: summary
                name: 摘要
                agents: [love]
                trigger: 用户要求摘要
                ---
                第一行说明。
                第二行细节。
                """;
        BuiltInSkill skill = SkillRepository.parse("summary.md", content);
        assertEquals("第一行说明。", skill.description());
    }

    @Test
    void missingRequiredFieldIsRejected() {
        String content = """
                ---
                id: broken
                name: 缺触发条件
                agents: [love]
                ---
                正文
                """;
        IllegalArgumentException cause = assertThrows(IllegalArgumentException.class,
                () -> SkillRepository.parse("broken.md", content));
        assertTrue(cause.getMessage().contains("trigger"));
    }

    @Test
    void missingFrontMatterMarkersAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillRepository.parse("bad.md", "id: no-markers\n"));
        assertThrows(IllegalArgumentException.class,
                () -> SkillRepository.parse("bad.md", "---\nid: unclosed\n"));
    }

    @Test
    void invalidFileIsSkippedButValidOnesLoad() throws IOException {
        write("00-broken.md", "id: no-front-matter\n");
        write("01-valid.md", FULL_SKILL);
        SkillRepository repository = new SkillRepository(tempDir);
        List<BuiltInSkill> catalog = repository.reload();
        assertEquals(1, catalog.size());
        assertEquals("web-research", catalog.getFirst().id());
        assertEquals(SkillRepository.SOURCE_MARKDOWN, catalog.getFirst().source());
    }

    @Test
    void duplicateIdKeepsFirstFile() throws IOException {
        write("01-first.md", FULL_SKILL);
        write("02-second.md", """
                ---
                id: web-research
                name: 重复技能
                agents: [love]
                trigger: 另一个触发
                ---
                正文
                """);
        List<BuiltInSkill> catalog = new SkillRepository(tempDir).reload();
        assertEquals(1, catalog.size());
        assertEquals("联网查询", catalog.getFirst().name());
    }

    @Test
    void emptyDirectoryFallsBackToBuiltinWebResearch() {
        SkillRepository repository = new SkillRepository(tempDir);
        List<BuiltInSkill> catalog = repository.reload();
        assertEquals(1, catalog.size());
        BuiltInSkill fallback = catalog.getFirst();
        assertEquals("web-research", fallback.id());
        assertEquals(SkillRepository.SOURCE_BUILTIN, fallback.source());
        assertEquals(List.of("webSearch"), fallback.tools());
    }

    @Test
    void missingDirectoryFallsBackToBuiltinWebResearch() {
        SkillRepository repository = new SkillRepository(tempDir.resolve("does-not-exist"));
        List<BuiltInSkill> catalog = repository.reload();
        assertEquals("web-research", catalog.getFirst().id());
        assertEquals(SkillRepository.SOURCE_BUILTIN, catalog.getFirst().source());
    }

    @Test
    void reloadPicksUpNewSkillFiles() throws IOException {
        write("05-web.md", FULL_SKILL);
        SkillRepository repository = new SkillRepository(tempDir);
        assertEquals(1, repository.reload().size());
        write("10-summarizer.md", """
                ---
                id: summarizer
                name: 摘要
                agents: [love]
                trigger: 用户要求摘要
                ---
                压缩长文本。
                """);
        List<BuiltInSkill> catalog = repository.reload();
        assertEquals(2, catalog.size());
        assertEquals("summarizer", catalog.getFirst().id());
    }
}
