package com.zwx.zwxagent.capability.mcp;

import java.util.List;

public record McpServerConfigurationRequest(String name, String endpoint, boolean enabled,
                                            String authType, String authHeaderName,
                                            String authSecret, boolean clearAuthSecret,
                                            List<String> allowedTools) {
}
