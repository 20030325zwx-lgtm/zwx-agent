package com.zwx.zwxagent.capability.mcp;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录本轮从已启用的 MCP 服务发现并被 allowlist 放行的工具名。
 * ToolGuard 用它区分“未知工具”和“管理员已授权的 MCP 工具”。
 */
public final class McpToolRegistry {

    private static final ConcurrentHashMap<String, Set<String>> TOOLS_BY_TENANT = new ConcurrentHashMap<>();

    private McpToolRegistry() {
    }

    public static void replace(String tenantId, Collection<String> toolNames) {
        if (tenantId == null || tenantId.isBlank()) return;
        TOOLS_BY_TENANT.put(tenantId, Set.copyOf(toolNames));
    }

    public static boolean allows(String tenantId, String toolName) {
        if (tenantId == null || toolName == null) return false;
        return TOOLS_BY_TENANT.getOrDefault(tenantId, Set.of()).contains(toolName);
    }
}
