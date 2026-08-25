package com.zwx.zwxagent.skills;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** Resolves the skills and tool callbacks that are authorized for one chat turn. */
@Component
public class BuiltInSkillRegistry {
    private static final BuiltInSkill WEB_RESEARCH = new BuiltInSkill(
            "web-research", "联网查询", "检索可能变化的公开信息，并以工具返回内容为依据回答。",
            "用户询问天气、交通、营业时间、价格、新闻、活动或其他时效性事实，且本轮已开启联网查询。",
            Set.of("love", "travel", "test"));

    private final ToolCallback[] webResearchTools;
    private final SkillConfigurationService configurationService;

    @Autowired
    public BuiltInSkillRegistry(@Qualifier("travelTools") ToolCallback[] webResearchTools,
                                SkillConfigurationService configurationService) {
        this.webResearchTools = webResearchTools;
        this.configurationService = configurationService;
    }

    /** Small constructor used by isolated unit tests without a database. */
    public BuiltInSkillRegistry(ToolCallback[] webResearchTools) {
        this.webResearchTools = webResearchTools;
        this.configurationService = null;
    }

    public List<BuiltInSkill> catalogFor(String agentKey) {
        return List.of(WEB_RESEARCH).stream().filter(skill -> skill.agentKeys().contains(agentKey)).toList();
    }

    public List<BuiltInSkill> availableFor(String tenantId, String agentKey, boolean webSearchEnabled) {
        if (!webSearchEnabled || !isEnabled(tenantId, agentKey, WEB_RESEARCH.id()) || !WEB_RESEARCH.agentKeys().contains(agentKey)) return List.of();
        return List.of(WEB_RESEARCH);
    }

    public List<BuiltInSkill> availableFor(String agentKey, boolean webSearchEnabled) {
        return availableFor("default", agentKey, webSearchEnabled);
    }

    public ToolCallback[] toolCallbacksFor(String tenantId, String agentKey, boolean webSearchEnabled) {
        return availableFor(tenantId, agentKey, webSearchEnabled).isEmpty() ? new ToolCallback[0] : webResearchTools;
    }

    public ToolCallback[] toolCallbacksFor(String agentKey, boolean webSearchEnabled) {
        return toolCallbacksFor("default", agentKey, webSearchEnabled);
    }

    public boolean isEnabled(String tenantId, String agentKey, String skillId) {
        if (!catalogFor(agentKey).stream().anyMatch(skill -> skill.id().equals(skillId))) return false;
        return configurationService == null || configurationService.isEnabled(tenantId, agentKey, skillId);
    }

    public void saveConfiguration(String tenantId, String agentKey, Set<String> enabledSkillIds) {
        if (configurationService == null) throw new IllegalStateException("Skill configuration persistence is unavailable");
        Set<String> knownIds = catalogFor(agentKey).stream().map(BuiltInSkill::id).collect(java.util.stream.Collectors.toSet());
        if (knownIds.isEmpty()) throw new IllegalArgumentException("Unknown or unsupported Skill agent: " + agentKey);
        if (!knownIds.containsAll(enabledSkillIds)) throw new IllegalArgumentException("Unknown Skill for agent: " + agentKey);
        configurationService.save(tenantId, agentKey, enabledSkillIds);
    }

    public List<SkillCatalogItem> catalogWithConfiguration(String tenantId, String agentKey) {
        return catalogFor(agentKey).stream()
                .map(skill -> new SkillCatalogItem(skill.id(), skill.name(), skill.description(), skill.trigger(), isEnabled(tenantId, agentKey, skill.id())))
                .toList();
    }
}
