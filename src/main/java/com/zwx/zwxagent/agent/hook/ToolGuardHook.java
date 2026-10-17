package com.zwx.zwxagent.agent.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.tools.ToolGuardPolicy;
import com.zwx.zwxagent.tools.UrlAccessPolicy;
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
 * 未知工具放行（新工具不因缺少策略注册而被误伤，由工具内部校验兜底）。
 */
@Slf4j
@Component
public class ToolGuardHook implements AgentHook {

    private final boolean enabled;
    private final Set<String> terminalAllowlist;
    private final UrlAccessPolicy urlAccessPolicy;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolGuardHook(@Value("${app.agent.hook.tool-guard-enabled:true}") boolean enabled,
                         @Value("${app.tools.terminal-allowlist:}") String terminalAllowlist,
                         UrlAccessPolicy urlAccessPolicy) {
        this.enabled = enabled;
        this.terminalAllowlist = terminalAllowlist == null || terminalAllowlist.isBlank()
                ? Set.of() : java.util.Arrays.stream(terminalAllowlist.split(","))
                        .map(String::trim).filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
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
            String reason = violationOf(call);
            if (reason != null) {
                log.warn("[tool-guard] 拦截工具调用 agent={} conversation={} step={} tool={} args={} 原因={}",
                        context.agentKey(), context.conversationId(), context.currentStep(),
                        call.name(), call.arguments(), reason);
                throw new HookAbortException("工具 " + call.name() + " 被安全策略拦截：" + reason);
            }
        }
    }

    /** @return null=放行；非 null=拒绝原因 */
    private String violationOf(PlannedToolCall call) {
        Map<String, Object> args = parseArguments(call.arguments());
        if (args == null) {
            return isGuardedTool(call.name()) ? "工具参数不是有效 JSON" : null;
        }
        return switch (call.name()) {
            case "executeTerminalCommand" -> ToolGuardPolicy.terminalViolation(text(args, "command"), terminalAllowlist);
            case "readFile", "writeFile", "generatePDF" -> ToolGuardPolicy.relativePathViolation(text(args, "fileName"));
            case "downloadResource" -> firstNonBlank(
                    urlViolation(text(args, "url")),
                    ToolGuardPolicy.relativePathViolation(text(args, "fileName")));
            case "scrapeWebPage" -> urlViolation(text(args, "url"));
            default -> null;
        };
    }

    private boolean isGuardedTool(String toolName) {
        return switch (toolName) {
            case "executeTerminalCommand", "readFile", "writeFile", "generatePDF",
                    "downloadResource", "scrapeWebPage" -> true;
            default -> false;
        };
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

    /** 参数 JSON 解析失败返回 null（放行）；参数缺失返回空 Map（按缺参拒绝）。 */
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
