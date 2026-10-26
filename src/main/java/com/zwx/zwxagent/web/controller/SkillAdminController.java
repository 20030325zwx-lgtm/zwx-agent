package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.capability.skills.BuiltInSkillRegistry;
import com.zwx.zwxagent.capability.skills.SkillCatalogItem;
import com.zwx.zwxagent.capability.skills.SkillConfigurationRequest;
import com.zwx.zwxagent.capability.skills.SkillRepository;
import com.zwx.zwxagent.platform.security.CurrentActor;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 内置 Skill 目录与按租户/智能体的启停配置。原 AiController skills 组拆分，路径保持不变。 */
@RestController
@RequestMapping("/ai")
public class SkillAdminController {

    @Resource
    private BuiltInSkillRegistry builtInSkillRegistry;

    @Resource
    private SkillRepository skillRepository;

    @GetMapping("/skills/catalog")
    public List<SkillCatalogItem> listSkillCatalog(CurrentActor actor, @RequestParam String agentKey) {
        return builtInSkillRegistry.catalogWithConfiguration(actor.tenantId(), agentKey);
    }

    @PostMapping("/skills/config")
    public List<SkillCatalogItem> saveSkillConfiguration(CurrentActor actor, @RequestBody SkillConfigurationRequest request) {
        actor.requireAdmin();
        if (request == null || request.agentKey() == null || request.enabledSkillIds() == null) throw new IllegalArgumentException("Invalid Skill configuration");
        builtInSkillRegistry.saveConfiguration(actor.tenantId(), request.agentKey(), request.enabledSkillIds());
        return builtInSkillRegistry.catalogWithConfiguration(actor.tenantId(), request.agentKey());
    }

    @PostMapping("/skills/reload")
    public List<SkillCatalogItem> reloadSkills(CurrentActor actor, @RequestParam String agentKey) {
        actor.requireAdmin();
        skillRepository.reload();
        return builtInSkillRegistry.catalogWithConfiguration(actor.tenantId(), agentKey);
    }
}
