package com.zwx.zwxagent.agent.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 智能体业务指标（Micrometer）：run 结果分布、工具拦截/确认、检索空命中、意图分流、配额拒绝。
 * 只承载聚合计数与结果枚举，不携带用户/租户标识；经独立管理端口 8124 暴露给 Prometheus。
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** run 收尾结果：completed / interrupted / failed（含挂起等待确认的 interrupted 计数见 runPaused）。 */
    public void runFinished(String outcome) {
        if (outcome == null || outcome.isBlank()) return;
        registry.counter("agent.run.finished", "outcome", outcome.toLowerCase()).increment();
    }

    /** run 挂起等待工具确认。 */
    public void runPausedForConfirmation() {
        registry.counter("agent.run.paused", "reason", "tool-confirmation").increment();
    }

    /** 工具被安全策略/确认门禁拒绝；tool 取工具名（MCP 工具名含提供方前缀）。 */
    public void toolDenied(String tool, String reason) {
        registry.counter("agent.tool.denied", "tool", safe(tool), "reason", safe(reason)).increment();
    }

    /** 副作用工具确认决定：approved / denied。 */
    public void toolConfirmationDecided(String decision) {
        registry.counter("agent.tool.confirmation", "decision", safe(decision)).increment();
    }

    /** 检索空命中：知识库有文档但召回为空，是评估检索质量与语料覆盖度的关键信号。 */
    public void ragRecallEmpty(String agentKey) {
        registry.counter("agent.rag.recall.empty", "agent", safe(agentKey)).increment();
    }

    /** 检索降级（超时/异常/过载），降级不阻断回答但必须可见。 */
    public void ragDegraded(String reason) {
        registry.counter("agent.rag.degraded", "reason", safe(reason)).increment();
    }

    /** 意图分流动作：auto-knowledge / auto-web / short-circuit / fallback。 */
    public void intentDecision(String action) {
        registry.counter("agent.intent.decision", "action", safe(action)).increment();
    }

    /** 配额/限流拒绝：concurrent / hourly / token-budget。 */
    public void quotaRejected(String kind) {
        registry.counter("agent.quota.rejected", "kind", safe(kind)).increment();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String normalized = value.trim().toLowerCase().replace(' ', '-');
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }
}
