package com.zwx.zwxagent.capability.mcp;

import java.util.List;
import java.util.Map;

public record McpToolDescriptor(String name, String description, String inputSchema,
                                List<String> requiredParameters, List<String> parameters) {
}
