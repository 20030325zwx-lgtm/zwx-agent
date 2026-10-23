package com.zwx.zwxagent.agent.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.capability.tools.ToolGuardPolicy;
import com.zwx.zwxagent.capability.tools.UrlAccessPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具调用安全护栏（拦截型）：beforeToolCalls 中央校验入口，
 * 按「工具名 → 参数策略」对计划调用做预检，越权参数抛 HookAbortException 短路本 step。
 * 工具内部校验（resolveWithin / UrlAccessPolicy / 本策略）保留为第二道防线，本 hook 是统一入口与审计点。
 * 未登记工具默认拒绝；配置 allowlist 只表示管理员显式允许该工具名，不替代工具自身的参数与资源授权。
 */
@Slf4j
@Component
public class ToolGuardHook implements AgentHook {

    private final boolean enabled;
    private final Set<String> terminalAllowlist;
    private final Set<String> allowedUnregisteredTools;
    private final UrlAccessPolicy urlAccessPolicy;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolGuardHook(@Value("${app.agent.hook.tool-guard-enabled:true}") boolean enabled,
                         @Value("${app.tools.terminal-allowlist:}") String terminalAllowlist,
                         @Value("${app.agent.hook.allowed-unregistered-tools:}") String allowedUnregisteredTools,
                         UrlAccessPolicy urlAccessPolicy) {
        this.enabled = enabled;
        this.terminalAllowlist = terminalAllowlist == null || terminalAllowlist.isBlank()
                ? Set.of() : java.util.Arrays.stream(terminalAllowlist.split(","))
                        .map(String::trim).filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.allowedUnregisteredTools = parseNames(allowedUnregisteredTools);
        this.urlAccessPolicy = urlAccessPolicy;
    }

    @Override
    public String id() {
        return "tool-guard";
    }

    @Override
    public int order() {
        return 150;
    }

    @Override
    public void beforeToolCalls(AgentRunContext context, List<PlannedToolCall> plannedCalls) {
        if (!enabled) {
            return;
        }
        for (PlannedToolCall call : plannedCalls) {
            String reason = violationOf(context, call);
            if (reason != null) {
                log.warn("[tool-guard] 拦截工具调用 agent={} conversation={} step={} tool={} argsSummary={} 原因={}",
                        context.agentKey(), context.conversationId(), context.currentStep(),
                        call.name(), argumentSummary(call.arguments()), reason);
                throw new HookAbortException("工具 " + call.name() + " 被安全策略拦截：" + reason);
            }
        }
    }

    /** @return null=放行；非 null=拒绝原因 */
    private String violationOf(AgentRunContext context, PlannedToolCall call) {
        Map<String, Object> args = parseArguments(call.arguments());
        if (args == null) {
            if (isKnownTool(call.name())) return "工具参数不是有效 JSON";
            return isAllowedTool(context.tenantId(), call.name()) ? null : "工具未登记，默认拒绝";
        }
        return switch (call.name()) {
            case "executeTerminalCommand" -> ToolGuardPolicy.terminalViolation(text(args, "command"), terminalAllowlist);
            case "readFile", "writeFile", "generatePDF" -> ToolGuardPolicy.relativePathViolation(text(args, "fileName"));
            case "downloadResource" -> firstNonBlank(
                    urlViolation(text(args, "url")),
                    ToolGuardPolicy.relativePathViolation(text(args, "fileName")));
            case "scrapeWebPage" -> urlViolation(text(args, "url"));
            case "searchWeb", "webSearch" -> null;
            case "executeDatabaseQuery" -> com.zwx.zwxagent.capability.tools.DatabaseQueryTool.validateSyntax(text(args, "sql"));
            case "queryExternalDatabase" -> firstNonBlank(
                    com.zwx.zwxagent.capability.tools.DatabaseQueryTool.validateSyntax(text(args, "sql")),
                    externalDatabaseTargetViolation(args));
            default -> isAllowedTool(context.tenantId(), call.name()) ? null : "工具未登记，默认拒绝";
        };
    }

    private String externalDatabaseTargetViolation(Map<String, Object> args) {
        String type = text(args, "databaseType");
        String host = text(args, "host");
        if (!"postgresql".equalsIgnoreCase(type) && !"mysql".equalsIgnoreCase(type)) {
            return "不支持的外部数据库类型";
        }
        String portText = text(args, "port");
        int port;
        try {
            port = portText == null || portText.isBlank() ? ("mysql".equalsIgnoreCase(type) ? 3306 : 5432)
                    : Integer.parseInt(portText);
        } catch (NumberFormatException exception) {
            return "数据库端口无效";
        }
        if (port < 1 || port > 65535) return "数据库端口无效";
        return urlViolation("http://" + host + ":" + port + "/");
    }

    private boolean isAllowedTool(String tenantId, String toolName) {
        return allowedUnregisteredTools.contains(toolName)
                || com.zwx.zwxagent.capability.mcp.McpToolRegistry.allows(tenantId, toolName);
    }

    private boolean isKnownTool(String toolName) {
        return switch (toolName) {
            case "executeTerminalCommand", "readFile", "writeFile", "generatePDF",
                    "downloadResource", "scrapeWebPage", "searchWeb", "webSearch", "executeDatabaseQuery",
                    "queryExternalDatabase" -> true;
            default -> false;
        };
    }

    private Set<String> parseNames(String names) {
        if (names == null || names.isBlank()) return Set.of();
        return java.util.Arrays.stream(names.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String argumentSummary(String arguments) {
        if (arguments == null) return "<null>";
        Map<String, Object> parsed = parseArguments(arguments);
        return parsed == null ? "<invalid-json, chars=" + arguments.length() + ">"
                : "keys=" + parsed.keySet() + ", chars=" + arguments.length();
    }

    /** 复用 UrlAccessPolicy（SSRF 防护：仅公网 HTTP(S)），异常消息即拒绝原因。 */
    private String urlViolation(String url) {
        try {
            urlAccessPolicy.validateHttpUrl(url);
            return null;
        } catch (IllegalArgumentException exception) {
            return exception.getMessage();
        }
    }

    /** 参数 JSON 解析失败返回 null；受管工具和未登记工具均 fail-closed。 */
    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception exception) {
            return null;
        }
    }

    private String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        return first != null ? first : second;
    }
}
