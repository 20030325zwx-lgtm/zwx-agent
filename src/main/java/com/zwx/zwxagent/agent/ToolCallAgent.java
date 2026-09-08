package com.zwx.zwxagent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.zwx.zwxagent.agent.hook.AgentHooks;
import com.zwx.zwxagent.agent.hook.AgentRunContext;
import com.zwx.zwxagent.agent.hook.HookAbortException;
import com.zwx.zwxagent.agent.hook.PlannedToolCall;
import com.zwx.zwxagent.agent.model.AgentState;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 处理工具调用的基础代理类，具体实现了 think 和 act 方法，可以用作创建实例的父类
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    // 可用的工具
    private final ToolCallback[] availableTools;

    // 保存工具调用信息的响应结果（要调用那些工具）
    private ChatResponse toolCallChatResponse;

    // 工具调用管理者
    private final ToolCallingManager toolCallingManager;

    // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
    private final ChatOptions chatOptions;

    private boolean lastStepUsedTool;
    private boolean nextStepPromptAdded;
    private boolean lastThinkFailed;

    private List<ToolExecution> lastToolExecutions = List.of();

    private static final int THINK_MAX_ATTEMPTS = 3;
    private static final long THINK_RETRY_BASE_MILLIS = 1000;

    public ToolCallAgent(ToolCallback[] availableTools) {
        super();
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();
        // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
        this.chatOptions = DashScopeChatOptions.builder()
                .withInternalToolExecutionEnabled(false)
                .build();
    }

    @Override
    public String step() {
        lastThinkFailed = false;
        AgentRunContext runContext = currentRunContext();
        fireBeforeThink(runContext);
        boolean needToolCall = think();
        fireAfterThink(runContext, needToolCall);
        if (!needToolCall) {
            if (lastThinkFailed) {
                // 模型调用在重试后仍失败：显式失败并中断任务，绝不把上一轮的
                // 陈旧响应当作最终答案交付，也不把错误文案写进记忆。
                throw new IllegalStateException("模型调用在重试后仍然失败，任务已中止");
            }
            setState(AgentState.FINISHED);
            return toolCallChatResponse == null
                    ? "未能生成回答"
                    : toolCallChatResponse.getResult().getOutput().getText();
        }
        return act();
    }

    private AgentRunContext currentRunContext() {
        AgentRunContext active = activeRunContext();
        if (active != null) return active;
        return AgentRunContext.builder().agentName(getName()).build();
    }

    private void fireBeforeThink(AgentRunContext runContext) {
        if (AgentHooks.pipeline() != null) AgentHooks.pipeline().fireBeforeThink(runContext);
    }

    private void fireAfterThink(AgentRunContext runContext, boolean toolCallPlanned) {
        if (AgentHooks.pipeline() != null) AgentHooks.pipeline().fireAfterThink(runContext, toolCallPlanned);
    }

    @Override
    protected boolean streamStepAsActivity() {
        return lastStepUsedTool;
    }

    @Override
    protected List<ToolExecution> lastToolExecutions() {
        return lastToolExecutions;
    }

    /**
     * 处理当前状态并决定下一步行动
     *
     * @return 是否需要执行行动
     */
    @Override
    public boolean think() {
        lastStepUsedTool = false;
        lastToolExecutions = List.of();
        // 1、校验提示词，拼接用户提示词
        if (!nextStepPromptAdded && StrUtil.isNotBlank(getNextStepPrompt())) {
            UserMessage userMessage = new UserMessage(getNextStepPrompt());
            getMessageList().add(userMessage);
            nextStepPromptAdded = true;
        }
        // 2、调用 AI 大模型，获取工具调用结果
        List<Message> messageList = getMessageList();
        Prompt prompt = new Prompt(messageList, this.chatOptions);
        try {
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(getSystemPrompt())
                    .toolCallbacks(availableTools)
                    .call()
                    .chatResponse();
            // 记录响应，用于等下 Act
            this.toolCallChatResponse = chatResponse;
            // 3、解析工具调用结果，获取要调用的工具
            // 助手消息
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            // 获取要调用的工具列表
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            // 输出提示信息
            String result = assistantMessage.getText();
            log.info(getName() + "的思考：" + result);
            log.info(getName() + "选择了 " + toolCallList.size() + " 个工具来使用");
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s", toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining("\n"));
            log.info(toolCallInfo);
            // 如果不需要调用工具，返回 false
            if (toolCallList.isEmpty()) {
                // 只有不调用工具时，才需要手动记录助手消息
                getMessageList().add(assistantMessage);
                return false;
            } else {
                lastStepUsedTool = true;
                // 需要调用工具时，无需记录助手消息，因为调用工具时会自动记录
                return true;
            }
        } catch (Exception e) {
            // 瞬时故障（限流、网络抖动）先重试；重试耗尽后显式失败。
            // 不把错误文案写入记忆，避免污染后续轮次的上下文。
            for (int attempt = 1; attempt < THINK_MAX_ATTEMPTS; attempt++) {
                try {
                    Thread.sleep(THINK_RETRY_BASE_MILLIS * (1L << (attempt - 1)));
                    ChatResponse retried = getChatClient().prompt(prompt)
                            .system(getSystemPrompt())
                            .toolCallbacks(availableTools)
                            .call()
                            .chatResponse();
                    this.toolCallChatResponse = retried;
                    AssistantMessage retriedMessage = retried.getResult().getOutput();
                    List<AssistantMessage.ToolCall> retriedCalls = retriedMessage.getToolCalls();
                    log.info(getName() + "第 " + (attempt + 1) + " 次尝试成功");
                    if (retriedCalls.isEmpty()) {
                        getMessageList().add(retriedMessage);
                        return false;
                    }
                    lastStepUsedTool = true;
                    return true;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception retryError) {
                    log.warn(getName() + "第 " + (attempt + 1) + " 次尝试失败：" + retryError.getMessage());
                }
            }
            log.error(getName() + "的思考过程在重试后仍然失败：" + e.getMessage());
            lastThinkFailed = true;
            return false;
        }
    }

    /**
     * 执行工具调用并处理结果
     *
     * @return 执行结果
     */
    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具需要调用";
        }
        AgentRunContext runContext = currentRunContext();
        List<PlannedToolCall> plannedCalls = plannedCalls(toolCallChatResponse);
        if (AgentHooks.pipeline() != null) {
            try {
                AgentHooks.pipeline().fireBeforeToolCalls(runContext, plannedCalls);
            } catch (HookAbortException exception) {
                log.info(getName() + "的工具调用被 hook 拦截：" + exception.getMessage());
                return "工具调用被拦截：" + exception.getMessage();
            }
            toolCallChatResponse = applyPlannedCalls(toolCallChatResponse, plannedCalls);
        }
        // 调用工具
        Prompt prompt = new Prompt(getMessageList(), this.chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        // 记录消息上下文，conversationHistory 已经包含了助手消息和工具调用返回的结果
        setMessageList(toolExecutionResult.conversationHistory());
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
        List<ToolExecution> executions = new java.util.ArrayList<>(toolResponseMessage.getResponses().stream()
                .map(response -> new ToolExecution(response.name(), toolArguments(response.name()), response.responseData()))
                .toList());
        if (AgentHooks.pipeline() != null) {
            // hook 可原地修改 executions（如截断超长结果）；修改需同步回消息历史
            List<ToolExecution> beforeHooks = List.copyOf(executions);
            AgentHooks.pipeline().fireAfterToolCalls(runContext, executions);
            if (!executions.equals(beforeHooks)) {
                toolResponseMessage = rebuildToolResponseMessage(toolResponseMessage, executions);
                List<Message> history = new java.util.ArrayList<>(getMessageList());
                history.set(history.size() - 1, toolResponseMessage);
                setMessageList(history);
            }
        }
        lastToolExecutions = executions;
        if (AgentHooks.pipeline() != null) {
            AgentHooks.pipeline().fireAfterToolCalls(runContext, lastToolExecutions);
        }
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "工具 " + response.name() + " 返回的结果：" + response.responseData())
                .collect(Collectors.joining("\n"));
        log.info(results);
        return results;
    }

    /** 从模型响应中提取计划调用的工具（可变视图，供 hook 审查与转换）。 */
    static List<PlannedToolCall> plannedCalls(ChatResponse response) {
        return response.getResult().getOutput().getToolCalls().stream()
                .map(toolCall -> new PlannedToolCall(toolCall.id(), toolCall.type(), toolCall.name(), toolCall.arguments()))
                .collect(Collectors.toList());
    }

    /**
     * 若 hook 修改了调用计划（增删改参数），重建包含新调用列表的响应；
     * 计划未变化时原样返回，保证零开销与零行为变化。
     */
    static ChatResponse applyPlannedCalls(ChatResponse response, List<PlannedToolCall> plannedCalls) {
        AssistantMessage output = response.getResult().getOutput();
        List<AssistantMessage.ToolCall> original = output.getToolCalls();
        boolean unchanged = plannedCalls.size() == original.size();
        for (int i = 0; unchanged && i < plannedCalls.size(); i++) {
            AssistantMessage.ToolCall toolCall = original.get(i);
            PlannedToolCall planned = plannedCalls.get(i);
            unchanged = toolCall.id().equals(planned.id()) && toolCall.name().equals(planned.name())
                    && toolCall.arguments().equals(planned.arguments());
        }
        if (unchanged) return response;
        List<AssistantMessage.ToolCall> rebuilt = plannedCalls.stream()
                .map(planned -> new AssistantMessage.ToolCall(planned.id(), planned.type(), planned.name(), planned.arguments()))
                .toList();
        AssistantMessage rebuiltMessage = new AssistantMessage(output.getText(), output.getMetadata(), rebuilt);
        return new ChatResponse(List.of(new Generation(rebuiltMessage)), response.getMetadata());
    }

    /** 按修改后的 executions 重建工具响应消息（按 id 对应替换 responseData，顺序与原响应一致）。 */
    static ToolResponseMessage rebuildToolResponseMessage(ToolResponseMessage original, List<ToolExecution> executions) {
        java.util.Map<String, String> updatedResults = new java.util.HashMap<>();
        for (ToolExecution execution : executions) {
            updatedResults.merge(execution.name(), execution.result(), (first, ignored) -> first);
        }
        List<ToolResponseMessage.ToolResponse> rebuilt = original.getResponses().stream()
                .map(response -> {
                    String result = updatedResults.get(response.name());
                    return response.responseData().equals(result) ? response
                            : new ToolResponseMessage.ToolResponse(response.id(), response.name(),
                            result == null ? response.responseData() : result);
                }).toList();
        return new ToolResponseMessage(rebuilt, original.getMetadata());
    }

    private String toolArguments(String toolName) {
        return toolCallChatResponse.getResult().getOutput().getToolCalls().stream()
                .filter(toolCall -> toolCall.name().equals(toolName))
                .map(AssistantMessage.ToolCall::arguments)
                .findFirst()
                .orElse("");
    }
}
