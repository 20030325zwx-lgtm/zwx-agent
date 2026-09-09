package com.zwx.zwxagent.skills;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves the skills and tool callbacks that are authorized for one chat turn. */
@Component
public class BuiltInSkillRegistry {

    /** Tool alias used in skill front-matter; maps to the shared web-search callbacks. */
    public static final String WEB_SEARCH_TOOL = "webSearch";

    private final ToolCallback[] webResearchTools;
    private final SkillConfigurationService configurationService;
    private final SkillRepository skillRepository;

    @Autowired
    public BuiltInSkillRegistry(@Qualifier("travelTools") ToolCallback[] webResearchTools,
                                SkillConfigurationService configurationService,
                                SkillRepository skillRepository) {
        this.webResearchTools = webResearchTools;
        this.configurationService = configurationService;
        this.skillRepository = skillRepository;
    }

    /** Small constructor used by isolated unit tests without a database; falls back to built-in skills. */
    public BuiltInSkillRegistry(ToolCallback[] webResearchTools) {
        this(webResearchTools, null, new SkillRepository(Path.of("nonexistent-skills-dir-for-isolated-tests")));
    }

    public List<BuiltInSkill> catalogFor(String agentKey) {
        return skillRepository.catalog().stream().filter(skill -> skill.agentKeys().contains(agentKey)).toList();
    }

    public List<BuiltInSkill> availableFor(String tenantId, String agentKey, boolean webSearchEnabled) {
        List<BuiltInSkill> available = new ArrayList<>();
        for (BuiltInSkill skill : skillRepository.catalog()) {
            if (!skill.agentKeys().contains(agentKey)) continue;
            if (!isEnabled(tenantId, agentKey, skill.id())) continue;
            if (skill.tools().contains(WEB_SEARCH_TOOL) && !webSearchEnabled) continue;
            available.add(skill);
        }
        return List.copyOf(available);
    }

    public List<BuiltInSkill> availableFor(String agentKey, boolean webSearchEnabled) {
        return availableFor("default", agentKey, webSearchEnabled);
    }

    public ToolCallback[] toolCallbacksFor(String tenantId, String agentKey, boolean webSearchEnabled) {
        Set<ToolCallback> callbacks = new LinkedHashSet<>();
        for (BuiltInSkill skill : availableFor(tenantId, agentKey, webSearchEnabled)) {
            for (String tool : skill.tools()) {
                if (WEB_SEARCH_TOOL.equals(tool)) {
                    callbacks.addAll(Arrays.asList(webResearchTools));
                }
            }
        }
        return callbacks.toArray(new ToolCallback[0]);
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
        configurationService.save(tenantId, agentKey, knownIds, enabledSkillIds);
    }

    public List<SkillCatalogItem> catalogWithConfiguration(String tenantId, String agentKey) {
        return catalogFor(agentKey).stream()
                .map(skill -> new SkillCatalogItem(skill.id(), skill.name(), skill.description(), skill.trigger(),
                        isEnabled(tenantId, agentKey, skill.id()), skill.source()))
                .toList();
    }
}
