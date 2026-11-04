package com.zwx.zwxagent.capability.tools;

import com.zwx.zwxagent.agent.execution.AgentToolCallLedgerService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import java.util.function.Consumer;

/**
 * 包在工具外层，用 agent_tool_call 账本保证恢复时不会重复产生副作用。
 */
public final class IdempotentToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final String runId;
    private final AgentToolCallLedgerService ledger;
    private final Consumer<Long> changeSink;

    private IdempotentToolCallback(ToolCallback delegate, String runId, AgentToolCallLedgerService ledger,
                                   Consumer<Long> changeSink) {
        this.delegate = delegate;
        this.runId = runId;
        this.ledger = ledger;
        this.changeSink = changeSink;
    }

    public static ToolCallback[] wrapAll(String runId, ToolCallback[] tools, AgentToolCallLedgerService ledger) {
        return wrapAll(runId, tools, ledger, id -> { });
    }

    public static ToolCallback[] wrapAll(String runId, ToolCallback[] tools, AgentToolCallLedgerService ledger,
                                         Consumer<Long> changeSink) {
        if (ledger == null || runId == null || runId.isBlank() || tools == null) return tools;
        ToolCallback[] wrapped = new ToolCallback[tools.length];
        for (int i = 0; i < tools.length; i++) {
            wrapped[i] = new IdempotentToolCallback(tools[i], runId, ledger, changeSink);
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
        String workerKey = ToolCallWorkerContext.current();
        String invocationKey = ToolCallWorkerContext.nextInvocationKey();
        AgentToolCallLedgerService.Decision decision = invocationKey == null
                ? workerKey == null
                    ? ledger.begin(runId, toolName, input)
                    : ledger.begin(runId, workerKey, toolName, input)
                // A2：invocation 身份路径——重放幂等、在途等待不误判 UNKNOWN
                : ledger.beginInvocation(runId, null, invocationKey, workerKey, toolName, input);
        notifyChanged(decision.id());
        return switch (decision.mode()) {
            case REPLAY -> decision.cachedResult() == null ? "" : decision.cachedResult();
            case UNKNOWN -> decision.message();
            case PENDING_IN_FLIGHT -> decision.message();
            case EXECUTE -> execute(input, decision.id());
        };
    }

    private String execute(String input, Long ledgerId) {
        try {
            String result = delegate.call(input);
            ledger.succeed(ledgerId, result);
            notifyChanged(ledgerId);
            return result;
        } catch (RuntimeException exception) {
            ledger.fail(ledgerId, exception.getMessage());
            notifyChanged(ledgerId);
            throw exception;
        }
    }

    private void notifyChanged(Long id) {
        if (id == null) return;
        try { changeSink.accept(id); }
        catch (RuntimeException ignored) { /* Observability must not change tool execution. */ }
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
