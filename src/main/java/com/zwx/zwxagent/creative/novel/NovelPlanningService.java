package com.zwx.zwxagent.creative.novel;

import com.zwx.zwxagent.creative.domain.NovelChapterRecord;
import com.zwx.zwxagent.creative.repository.NovelChapterRepository;
import com.zwx.zwxagent.creative.service.CreativeProjectService;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.settings.DynamicChatModelFactory;
import com.zwx.zwxagent.platform.settings.ModelCatalogService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 新章剧情走向建议（AI 小说 v1）：读项目设定 + 章节目录 + 近几章概要，
 * 让 chat 模型给出若干组「标题 + 剧情走向」候选，用户采纳后作为运行输入。
 * 只做文本建议，不创建任务、不产生章节。
 */
@Service
public class NovelPlanningService {

    public static final int DIRECTION_MAX = 4000;
    private static final int SUGGESTION_COUNT = 3;
    private static final int SUMMARY_CONTEXT_COUNT = 3;

    private static final String SYSTEM = """
            你是网文主编。根据作品设定、章节目录、近几章概要与用户给定的意向，为下一章提出 %d 组剧情走向候选。
            每组包含 title（本章标题，不超过 20 字）与 direction（本章剧情走向，80-150 字，
            写明冲突、人物行动与结尾钩子）。候选之间要有明显差异（如推进主线 / 展开支线 / 埋设伏笔）。
            只输出 JSON 数组：[{"title":"...","direction":"..."},...]，不要输出解释或 Markdown。
            """.formatted(SUGGESTION_COUNT);

    public record PlotSuggestion(String title, String direction) {
    }

    public record SuggestionsView(List<PlotSuggestion> items) {
    }

    private final ModelCatalogService catalogService;
    private final DynamicChatModelFactory chatModelFactory;
    private final NovelChapterRepository chapterRepository;
    private final CreativeProjectService projectService;

    public NovelPlanningService(ModelCatalogService catalogService, DynamicChatModelFactory chatModelFactory,
                                NovelChapterRepository chapterRepository, CreativeProjectService projectService) {
        this.catalogService = catalogService;
        this.chatModelFactory = chatModelFactory;
        this.chapterRepository = chapterRepository;
        this.projectService = projectService;
    }

    public SuggestionsView suggest(CurrentActor actor, UUID projectId, String direction) {
        var settings = projectService.settingsOf(actor, projectId);
        List<NovelChapterRecord> chapters = chapterRepository
                .listByProject(actor.tenantId(), actor.userId(), projectId);
        String intent = direction == null || direction.isBlank() ? "（用户未指定，自由发挥）" : direction.trim();
        if (intent.length() > DIRECTION_MAX) {
            throw new IllegalArgumentException("剧情意向超过 " + DIRECTION_MAX + " 字符");
        }
        String prompt = buildPrompt(settings, chapters, intent);
        var selection = catalogService.resolve(actor.userId(), ModelCatalogService.PURPOSE_CHAT);
        String raw;
        try {
            ChatClient client = ChatClient.builder(chatModelFactory.model(selection.config())).build();
            raw = client.prompt().system(SYSTEM).user(prompt).call().content();
        } catch (Exception exception) {
            throw new IllegalStateException("剧情建议生成失败：" + rootMessage(exception), exception);
        }
        List<PlotSuggestion> items = parseSuggestions(raw);
        if (items.isEmpty()) {
            throw new IllegalStateException("AI 未返回有效的剧情建议，请重试");
        }
        return new SuggestionsView(items);
    }

    private String buildPrompt(Map<String, Object> settings, List<NovelChapterRecord> chapters, String intent) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("【作品设定】\n").append(settingsBlock(settings)).append("\n\n");
        prompt.append("【已有章节】\n");
        if (chapters.isEmpty()) {
            prompt.append("（尚无章节，这是第一章）\n");
        } else {
            for (var chapter : chapters) {
                prompt.append("第").append(chapter.chapterNumber()).append("章 ").append(chapter.title()).append("\n");
            }
            prompt.append("\n【近几章概要】\n");
            List<NovelChapterRecord> recent = chapters.stream()
                    .skip(Math.max(0, chapters.size() - SUMMARY_CONTEXT_COUNT)).toList();
            for (var chapter : recent) {
                prompt.append("第").append(chapter.chapterNumber()).append("章：")
                        .append(chapter.summary() == null || chapter.summary().isBlank()
                                ? "（无概要）" : chapter.summary()).append("\n");
            }
        }
        prompt.append("\n【下一章序号】第").append(
                chapters.isEmpty() ? 1 : chapters.getLast().chapterNumber() + 1).append("章\n");
        prompt.append("【用户意向】").append(intent).append("\n");
        return prompt.toString();
    }

    public static String settingsBlock(Map<String, Object> settings) {
        String characters = text(settings.get("characters"));
        String worldview = text(settings.get("worldview"));
        String style = text(settings.get("style"));
        if (characters.isEmpty() && worldview.isEmpty() && style.isEmpty()) return "（暂无设定）";
        StringBuilder block = new StringBuilder();
        if (!characters.isEmpty()) block.append("人物：").append(characters).append("\n");
        if (!worldview.isEmpty()) block.append("世界观：").append(worldview).append("\n");
        if (!style.isEmpty()) block.append("文风：").append(style).append("\n");
        return block.toString().stripTrailing();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 容忍代码围栏与多余文本：取首个 JSON 数组解析。 */
    static List<PlotSuggestion> parseSuggestions(String raw) {
        List<PlotSuggestion> items = new ArrayList<>();
        if (raw == null) return items;
        String text = raw.trim().replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) return items;
        try {
            var array = com.zwx.zwxagent.creative.support.CreativeJson.parse(text.substring(start, end + 1));
            for (var node : array) {
                String title = node.path("title").asText("").trim();
                String direction = node.path("direction").asText("").trim();
                if (title.isEmpty() || direction.isEmpty()) continue;
                items.add(new PlotSuggestion(title, direction));
                if (items.size() >= SUGGESTION_COUNT) break;
            }
        } catch (Exception ignored) {
            // 解析失败按空处理，由调用方明确报错
        }
        return items;
    }

    private static String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
