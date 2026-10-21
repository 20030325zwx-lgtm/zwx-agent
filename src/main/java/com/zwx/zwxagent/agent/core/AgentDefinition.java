package com.zwx.zwxagent.agent.core;

import java.util.Set;

public record AgentDefinition(String key, String name, String systemPrompt, Set<String> capabilities) {
}
