package com.zwx.zwxagent.capability.tools;

import java.util.Map;

/**
 * 工具风险登记表：集中定义工具名 → 风险等级，替代散落在 hook / 确认服务中的硬编码清单。
 * 未登记工具（包括 MCP 与插件扩展）按 EXECUTE 处理，保证新增能力默认需要确认而不是默认放行。
 */
public final class ToolRiskCatalog {

    private static final Map<String, ToolRisk> RISKS = Map.ofEntries(
            // 只读检索
            Map.entry("readFile", ToolRisk.READ_ONLY),
            Map.entry("searchWeb", ToolRisk.READ_ONLY),
            Map.entry("webSearch", ToolRisk.READ_ONLY),
            Map.entry("scrapeWebPage", ToolRisk.READ_ONLY),
            Map.entry("getCurrentTime", ToolRisk.READ_ONLY),
            Map.entry("listProjectTree", ToolRisk.READ_ONLY),
            Map.entry("readProjectFile", ToolRisk.READ_ONLY),
            Map.entry("searchProjectCode", ToolRisk.READ_ONLY),
            Map.entry("getProjectGitStatus", ToolRisk.READ_ONLY),
            Map.entry("getProjectDiff", ToolRisk.READ_ONLY),
            Map.entry("listProjectTasks", ToolRisk.READ_ONLY),
            // 交付物写入
            Map.entry("writeFile", ToolRisk.WRITE),
            Map.entry("generatePDF", ToolRisk.WRITE),
            // 执行/修改类
            Map.entry("applyProjectPatch", ToolRisk.EXECUTE),
            Map.entry("runProjectTask", ToolRisk.EXECUTE),
            Map.entry("downloadResource", ToolRisk.EXECUTE),
            // 图片创作会真实产生供应商费用：默认询问，不允许静默执行
            Map.entry("generateImage", ToolRisk.EXECUTE),
            Map.entry("executeDatabaseQuery", ToolRisk.EXECUTE),
            // 高危
            Map.entry("executeTerminalCommand", ToolRisk.DESTRUCTIVE),
            Map.entry("commitProjectChanges", ToolRisk.DESTRUCTIVE),
            Map.entry("queryExternalDatabase", ToolRisk.DESTRUCTIVE));

    private ToolRiskCatalog() {
    }

    /** 未登记工具统一按 EXECUTE 处理：新工具/插件/MCP 默认走确认链路。 */
    public static ToolRisk riskOf(String toolName) {
        if (toolName == null || toolName.isBlank()) return ToolRisk.EXECUTE;
        ToolRisk risk = RISKS.get(toolName);
        if (risk != null) return risk;
        if (toolName.startsWith("mcp:")) return ToolRisk.EXECUTE;
        return ToolRisk.EXECUTE;
    }
}
