package com.zwx.zwxagent.capability.tools;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具风险登记表：集中定义工具名 → 风险等级，替代散落在 hook / 确认服务中的硬编码清单。
 * 未登记工具（包括 MCP 与插件扩展）按 EXECUTE 处理，保证新增能力默认需要确认而不是默认放行。
 *
 * <p>风险等级不能只看工具名，还要结合参数判断（动态分级）：{@link #dynamicRisk(String, String)}
 * 承载参数感知规则，命中时优先于静态登记（如终端白名单内的纯环境查询命令降为 READ_ONLY）。
 * 动态规则刻意保守：只有确定无副作用且不越出既有拦截面时才给更低等级，其余一律回落静态值。</p>
 */
public final class ToolRiskCatalog {

    private static final Map<String, ToolRisk> RISKS = Map.ofEntries(
            // 只读检索
            Map.entry("readFile", ToolRisk.READ_ONLY),
            Map.entry("searchWeb", ToolRisk.READ_ONLY),
            Map.entry("webSearch", ToolRisk.READ_ONLY),
            Map.entry("scrapeWebPage", ToolRisk.READ_ONLY),
            Map.entry("getCurrentTime", ToolRisk.READ_ONLY),
            // spawnSubagent 只是把执行单元派生出去，自身无副作用；
            // 子代理内部的工具调用各自走风险评级与确认（确认经共享 runId 冒泡）
            Map.entry("spawnSubagent", ToolRisk.READ_ONLY),
            Map.entry("listProjectTree", ToolRisk.READ_ONLY),
            Map.entry("readProjectFile", ToolRisk.READ_ONLY),
            Map.entry("searchProjectCode", ToolRisk.READ_ONLY),
            Map.entry("getProjectGitStatus", ToolRisk.READ_ONLY),
            Map.entry("getProjectDiff", ToolRisk.READ_ONLY),
            Map.entry("listProjectTasks", ToolRisk.READ_ONLY),
            // 结构化提问不产生副作用，不弹确认
            Map.entry("askHuman", ToolRisk.READ_ONLY),
            // 交付物写入
            Map.entry("writeFile", ToolRisk.WRITE),
            Map.entry("generatePDF", ToolRisk.WRITE),
            Map.entry("generateSpreadsheet", ToolRisk.WRITE),
            Map.entry("createDocument", ToolRisk.WRITE),
            Map.entry("createPresentation", ToolRisk.WRITE),
            Map.entry("createPdfFromMarkdown", ToolRisk.WRITE),
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
            // 极高危（L4）：接触外部生产库，读它也是数据外泄面；默认拒绝，需用户显式 ALLOW 规则解锁
            Map.entry("queryExternalDatabase", ToolRisk.FORBIDDEN));

    /** 终端命令中确定无副作用、不读取文件内容的「纯环境查询」命令：可安全降为 READ_ONLY。 */
    private static final Set<String> INERT_TERMINAL_COMMANDS = Set.of("ls", "pwd", "date", "echo");

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

    /** 静态登记 + 参数感知动态规则：动态命中时优先（可降可升），未命中回落静态值。 */
    public static ToolRisk riskOf(String toolName, String argumentsJson) {
        return dynamicRisk(toolName, argumentsJson).orElseGet(() -> riskOf(toolName));
    }

    /**
     * 参数感知动态分级。当前规则：
     * <ul>
     *   <li>executeTerminalCommand：命令首词 ∈ 纯环境查询白名单（ls/pwd/date/echo）且不含 shell
     *       元字符 → READ_ONLY。刻意排除 cat/grep/head/tail/find/python3——它们能读工作区外
     *       的宿主文件（信息面扩大），维持 DESTRUCTIVE 走确认。</li>
     * </ul>
     * 返回 empty 表示无动态规则命中，调用方回落静态登记。
     */
    public static Optional<ToolRisk> dynamicRisk(String toolName, String argumentsJson) {
        if ("executeTerminalCommand".equals(toolName)) {
            Map<String, Object> args = parseArguments(argumentsJson);
            if (args == null) return Optional.empty();
            Object command = args.get("command");
            if (command instanceof String text) {
                String trimmed = text.trim();
                if (trimmed.isEmpty()) return Optional.empty();
                for (int i = 0; i < trimmed.length(); i++) {
                    if (SHELL_METACHARACTERS.indexOf(trimmed.charAt(i)) >= 0) {
                        return Optional.empty();
                    }
                }
                String firstToken = trimmed.split("\\s+")[0];
                if (INERT_TERMINAL_COMMANDS.contains(firstToken)) {
                    return Optional.of(ToolRisk.READ_ONLY);
                }
            }
        }
        return Optional.empty();
    }

    /** 与 ToolGuardPolicy 的元字符拦截面保持一致；含元字符的命令会被守卫拒绝，无需动态降级。 */
    private static final String SHELL_METACHARACTERS = ";|&><`$\n\r";

    private static final com.fasterxml.jackson.databind.ObjectMapper ARGUMENT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return Map.of();
        try {
            return ARGUMENT_MAPPER.readValue(argumentsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception exception) {
            return null;
        }
    }
}
