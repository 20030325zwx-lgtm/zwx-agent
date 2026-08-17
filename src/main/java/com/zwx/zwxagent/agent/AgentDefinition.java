package com.zwx.zwxagent.agent;

import java.util.Set;

public record AgentDefinition(String key, String name, String systemPrompt, Set<String> capabilities) {
}
