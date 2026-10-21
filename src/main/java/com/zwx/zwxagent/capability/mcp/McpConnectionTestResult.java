package com.zwx.zwxagent.capability.mcp;

import java.util.List;

public record McpConnectionTestResult(boolean connected, String message, List<String> tools) {
}
