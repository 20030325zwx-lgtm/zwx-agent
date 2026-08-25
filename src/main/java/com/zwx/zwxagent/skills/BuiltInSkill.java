package com.zwx.zwxagent.skills;

import java.util.Set;

/** Metadata and trigger policy for a project-owned agent skill. */
public record BuiltInSkill(String id, String name, String description, String trigger, Set<String> agentKeys) {
}
