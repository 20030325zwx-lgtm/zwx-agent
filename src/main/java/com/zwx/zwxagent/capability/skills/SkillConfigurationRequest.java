package com.zwx.zwxagent.capability.skills;

import java.util.Set;

public record SkillConfigurationRequest(String agentKey, Set<String> enabledSkillIds) {
}
