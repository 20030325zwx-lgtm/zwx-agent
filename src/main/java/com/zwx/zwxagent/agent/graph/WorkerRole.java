package com.zwx.zwxagent.agent.graph;

import java.util.Locale;

/**
 * 工作者角色：每个角色只看到完成其子任务所需的工具子集。
 */
public enum WorkerRole {
    RESEARCHER("researcher", "调研员", "联网调研员：检索、抓取与下载公开资料",
            SetOf("webSearch", "scrapeWebPage", "downloadResource")),
    AUTHOR("author", "撰写员", "文档撰写员：读写工作区文件并生成 PDF 交付物",
            SetOf("readFile", "writeFile", "generatePDF")),
    ANALYST("analyst", "分析员", "数据分析员：执行只读 SQL 查询与白名单命令分析数据",
            SetOf("executeDatabaseQuery", "queryExternalDatabase", "executeTerminalCommand")),
    GENERAL("general", "通用助理", "通用助理：可以使用全部可用工具（含 MCP）",
            null);

    private final String key;
    private final String displayName;
    private final String description;
    private final java.util.Set<String> allowedTools;

    WorkerRole(String key, String displayName, String description, java.util.Set<String> allowedTools) {
        this.key = key;
        this.displayName = displayName;
        this.description = description;
        this.allowedTools = allowedTools;
    }

    public String key() {
        return key;
    }

    /** 展示用中文名（调研员/撰写员/分析员/通用助理） */
    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /** 返回该角色允许的工具名集合；null 表示不限制（general）。 */
    public java.util.Set<String> allowedTools() {
        return allowedTools;
    }

    public static WorkerRole fromKey(String value) {
        if (value == null) return GENERAL;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (WorkerRole role : values()) {
            if (role.key.equals(normalized)) return role;
        }
        return GENERAL;
    }

    /** 工具名的中文动作标签，用于面向用户的执行事件。 */
    public static String toolLabel(String toolName) {
        return switch (toolName == null ? "" : toolName) {
            case "webSearch" -> "联网搜索";
            case "scrapeWebPage" -> "抓取网页";
            case "downloadResource" -> "下载资源";
            case "readFile" -> "读取文件";
            case "writeFile" -> "写入文件";
            case "generatePDF" -> "生成 PDF";
            case "executeDatabaseQuery" -> "查询数据库";
            case "queryExternalDatabase" -> "查询外部数据库";
            case "executeTerminalCommand" -> "执行命令";
            case "" -> "处理任务";
            default -> "执行 " + toolName;
        };
    }

    private static java.util.Set<String> SetOf(String... values) {
        return java.util.Arrays.stream(values)
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
