package com.zwx.zwxagent.agent.hook;

import com.zwx.zwxagent.agent.BaseAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具结果截断 hook：超长工具结果原地截断（见 AgentHook.afterToolCalls 的原地修改约定），
 * 防止单次工具返回撑爆模型上下文。order 在 ExecutionTraceHook 之后，保证轨迹记录的是完整结果。
 */
@Slf4j
@Component
public class ToolResultTruncationHook implements AgentHook {

    static final String TRUNCATION_MARKER_TEMPLATE = "\n...(结果过长已截断，原始长度 %d 字符)...\n";

    private final int maxChars;

    public ToolResultTruncationHook(@Value("${app.agent.hook.tool-result-max-chars:8000}") int maxChars) {
        this.maxChars = Math.max(500, maxChars);
    }

    @Override
    public String id() {
        return "tool-result-truncation";
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public void afterToolCalls(AgentRunContext context, List<BaseAgent.ToolExecution> executions) {
        for (int i = 0; i < executions.size(); i++) {
            BaseAgent.ToolExecution execution = executions.get(i);
            if (execution.result() != null && execution.result().length() > maxChars) {
                executions.set(i, new BaseAgent.ToolExecution(execution.name(), execution.arguments(),
                        truncate(execution.result())));
            }
        }
    }

    /** 前 70% + 省略标记（含原始长度）+ 后 30%。 */
    private String truncate(String result) {
        String marker = TRUNCATION_MARKER_TEMPLATE.formatted(result.length());
        int head = (int) (maxChars * 0.7);
        int tail = Math.max(0, maxChars - head - marker.length());
        return result.substring(0, head) + marker + result.substring(result.length() - tail);
    }
}
