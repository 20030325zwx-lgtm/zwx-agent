package com.zwx.zwxagent.skills;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads agent skills from markdown files with front-matter metadata.
 * <p>
 * Design (design/plans/2026-09-08-memory-skills-workspace.md §3.2):
 * an empty directory or any parse failure must never block startup —
 * the loader falls back to the built-in web-research skill instead.
 */
@Component
public class SkillRepository {

    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_MARKDOWN = "markdown";

    private static final Logger log = LoggerFactory.getLogger(SkillRepository.class);

    /** Mirror of the pre-batch-B hardcoded skill; used whenever the markdown catalog is empty. */
    static final BuiltInSkill FALLBACK_WEB_RESEARCH = new BuiltInSkill(
            "web-research", "联网查询", "检索可能变化的公开信息，并以工具返回内容为依据回答。",
            "用户询问天气、交通、营业时间、价格、新闻、活动或其他时效性事实，且本轮已开启联网查询。",
            Set.of("love", "travel", "test"), List.of("webSearch"),
            "检索可能变化的公开信息，并以工具返回内容为依据回答。", SOURCE_BUILTIN);

    private final Path directory;
    private volatile List<BuiltInSkill> catalog = List.of(FALLBACK_WEB_RESEARCH);

    @Autowired
    public SkillRepository(@Value("${app.skills.dir:skills}") String directory) {
        this(Path.of(directory));
    }

    SkillRepository(Path directory) {
        this.directory = directory;
    }

    @PostConstruct
    void loadAtStartup() {
        try {
            reload();
        } catch (RuntimeException cause) {
            log.warn("[skills] 技能目录首次加载失败，使用内置回落技能：{}", cause.getMessage());
        }
    }

    /** Rescans the skill directory. Returns the new catalog (fallback when nothing valid was loaded). */
    public synchronized List<BuiltInSkill> reload() {
        List<BuiltInSkill> loaded = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> markdownFiles = files
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path file : markdownFiles) {
                String fileName = file.getFileName().toString();
                try {
                    BuiltInSkill skill = parse(fileName, Files.readString(file, StandardCharsets.UTF_8));
                    if (!seenIds.add(skill.id())) {
                        log.warn("[skills] 技能 id 重复，跳过 {}（id={} 已存在）", fileName, skill.id());
                        continue;
                    }
                    loaded.add(skill);
                } catch (RuntimeException cause) {
                    log.warn("[skills] 跳过无效技能文件 {}：{}", fileName, cause.getMessage());
                }
            }
        } catch (IOException cause) {
            log.warn("[skills] 技能目录不可读（{}）：{}，回落到内置技能", directory, cause.getMessage());
        }
        if (loaded.isEmpty()) {
            log.info("[skills] 未加载到任何 markdown 技能，使用内置回落技能：{}", FALLBACK_WEB_RESEARCH.id());
            catalog = List.of(FALLBACK_WEB_RESEARCH);
        } else {
            loaded.sort(Comparator.comparing(BuiltInSkill::id));
            catalog = List.copyOf(loaded);
            log.info("[skills] 已加载 {} 个 markdown 技能：{}", loaded.size(),
                    loaded.stream().map(BuiltInSkill::id).toList());
        }
        return catalog;
    }

    public List<BuiltInSkill> catalog() {
        return catalog;
    }

    static BuiltInSkill parse(String fileName, String content) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("文件为空");
        List<String> lines = content.lines().map(String::trim).toList();
        int index = firstMeaningfulLine(lines);
        if (index < 0 || !"---".equals(lines.get(index))) {
            throw new IllegalArgumentException("缺少 front-matter 起始标记 ---");
        }
        Map<String, String> meta = new LinkedHashMap<>();
        index++;
        while (index < lines.size() && !"---".equals(lines.get(index))) {
            String line = lines.get(index);
            index++;
            if (line.isEmpty() || line.startsWith("#")) continue;
            int separator = line.indexOf(':');
            if (separator <= 0) throw new IllegalArgumentException("front-matter 行缺少 key: value：" + line);
            meta.put(line.substring(0, separator).trim().toLowerCase(), scalar(line.substring(separator + 1).trim()));
        }
        if (index >= lines.size()) throw new IllegalArgumentException("缺少 front-matter 结束标记 ---");
        StringBuilder body = new StringBuilder();
        for (index++; index < lines.size(); index++) {
            if (body.length() > 0) body.append('\n');
            body.append(lines.get(index));
        }
        String id = requireField(meta, "id");
        String name = requireField(meta, "name");
        String trigger = requireField(meta, "trigger");
        List<String> agents = arrayField(meta.get("agents"));
        if (agents.isEmpty()) throw new IllegalArgumentException("agents 不能为空");
        List<String> tools = arrayField(meta.get("tools"));
        String instruction = body.toString().trim();
        String description = scalar(meta.getOrDefault("description", ""));
        if (description.isBlank()) {
            description = firstLine(instruction, 120);
        }
        if (description.isBlank()) description = name;
        return new BuiltInSkill(id, name, description, trigger, Set.copyOf(agents), List.copyOf(tools),
                instruction, SOURCE_MARKDOWN);
    }

    private static int firstMeaningfulLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isEmpty()) return i;
        }
        return -1;
    }

    private static String requireField(Map<String, String> meta, String key) {
        String value = scalar(meta.getOrDefault(key, ""));
        if (value.isBlank()) throw new IllegalArgumentException("缺少必填字段 " + key);
        return value;
    }

    private static String scalar(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static List<String> arrayField(String value) {
        String trimmed = scalar(value == null ? "" : value);
        if (trimmed.isBlank()) return List.of();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw new IllegalArgumentException("数组字段必须写成 [a, b] 形式：" + trimmed);
        }
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) return List.of();
        List<String> items = new ArrayList<>();
        for (String item : inner.split(",")) {
            String normalized = scalar(item);
            if (!normalized.isEmpty()) items.add(normalized);
        }
        return items;
    }

    private static String firstLine(String text, int maxLength) {
        if (text == null || text.isBlank()) return "";
        String line = text.lines().filter(candidate -> !candidate.isBlank()).findFirst().orElse("").trim();
        return line.length() <= maxLength ? line : line.substring(0, maxLength);
    }
}
