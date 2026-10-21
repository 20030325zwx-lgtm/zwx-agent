package com.zwx.zwxagent.capability.tools;

import com.zwx.zwxagent.agent.execution.AgentToolCallLedgerService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 包在工具外层，用 agent_tool_call 账本保证恢复时不会重复产生副作用。
 */
public final class IdempotentToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final String runId;
    private final AgentToolCallLedgerService ledger;

    private IdempotentToolCallback(ToolCallback delegate, String runId, AgentToolCallLedgerService ledger) {
        this.delegate = delegate;
        this.runId = runId;
        this.ledger = ledger;
    }

    public static ToolCallback[] wrapAll(String runId, ToolCallback[] tools, AgentToolCallLedgerService ledger) {
        if (ledger == null || runId == null || runId.isBlank() || tools == null) return tools;
        ToolCallback[] wrapped = new ToolCallback[tools.length];
        for (int i = 0; i < tools.length; i++) {
            wrapped[i] = new IdempotentToolCallback(tools[i], runId, ledger);
        }
        return wrapped;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String input) {
        String toolName = toolName();
        AgentToolCallLedgerService.Decision decision = ledger.begin(runId, toolName, input);
        return switch (decision.mode()) {
            case REPLAY -> decision.cachedResult() == null ? "" : decision.cachedResult();
            case UNKNOWN -> decision.message();
            case EXECUTE -> execute(input, decision.id());
        };
    }

    private String execute(String input, Long ledgerId) {
        try {
            String result = delegate.call(input);
            ledger.succeed(ledgerId, result);
            return result;
        } catch (RuntimeException exception) {
            ledger.fail(ledgerId, exception.getMessage());
            throw exception;
        }
    }

    private String toolName() {
        try {
            ToolDefinition definition = delegate.getToolDefinition();
            return definition == null ? "unknown" : definition.name();
        } catch (Exception exception) {
            return "unknown";
        }
    }
}
