package com.zwx.zwxagent.skills;

import java.util.Set;

public record SkillConfigurationRequest(String agentKey, Set<String> enabledSkillIds) {
}
