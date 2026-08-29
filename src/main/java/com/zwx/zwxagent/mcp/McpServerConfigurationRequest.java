package com.zwx.zwxagent.mcp;

public record McpServerConfigurationRequest(String name, String endpoint, boolean enabled) {
}
