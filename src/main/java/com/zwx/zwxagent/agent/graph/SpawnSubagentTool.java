package com.zwx.zwxagent.agent.graph;

import com.zwx.zwxagent.agent.core.AgentRunIdentity;
import com.zwx.zwxagent.agent.core.BaseAgent;
import com.zwx.zwxagent.agent.core.ToolCallAgent;
import com.zwx.zwxagent.agent.hook.AgentHooks;
import com.zwx.zwxagent.agent.hook.HookAbortException;
import com.zwx.zwxagent.capability.tools.ToolCallWorkerContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子代理工具（「子代理 = 工具」终态架构的核心）：主循环模型按需派生独立上下文的 ReAct
 * 子代理执行子任务，结论压缩后回传。对标 Claude Code 的 Task 工具与 deepseek-harness 的
 * subagent session。
 *
 * <p>语义与边界：
 * <ul>
 *   <li>同步阻塞：调用返回子代理最终结论；并行 = 主循环一个 turn 发多个本工具调用，
 *       由 {@link com.zwx.zwxagent.capability.tools.ParallelizingToolCallingManager} 并行执行</li>
 *   <li>确认冒泡：子代理与主循环共享 runId，需确认的工具请求进入同一 run 的确认队列，
 *       run 挂起后用户批准、账本回放继续</li>
 *   <li>深度 = 1：子代理工具集 = 主循环工具集去掉本工具（不允许孙代理）</li>
 *   <li>数量闸：单 run 派生总数上限；超限返回可读错误让模型自行合并任务</li>
 *   <li>结果压缩：子代理提示词纪律（自包含/带证据/≤2000 字）+ 工具层硬截断</li>
 *   <li>恢复语义：spawn 记入幂等账本；run 恢复时子代理整体重跑（内部已成功工具由账本回放）</li>
 * </ul>
 */
@Slf4j
public class SpawnSubagentTool implements ToolCallback {

    public static final String TOOL_NAME = "spawnSubagent";
    static final int MAX_STEPS = 10;
    static final int RESULT_MAX_CHARS = 4000;

    private static final String TOOL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "task": {
                  "type": "string",
                  "description": "子任务的自包含描述：目标、输入材料、完成判据。子代理看不到主对话，必须把需要的上下文全部写在这里"
                }
              },
              "required": ["task"]
            }""";

    private final ChatModel chatModel;
    private final ToolCallback[] parentTools;
    private final String runId;
    private final String runKey;
    private final String tenantId;
    private final String userId;
    private final String conversationId;
    private final java.util.function.Consumer<ActivityEvent> activitySink;
    private final java.util.function.BooleanSupplier stopCheck;
    private final com.zwx.zwxagent.agent.observability.TokenUsageService tokenUsage;
    private final com.zwx.zwxagent.agent.observability.ModelIoService modelIo;
    private final com.zwx.zwxagent.agent.execution.ToolConfirmationService confirmationService;
    private final int maxTotalPerRun;
    private final AtomicInteger spawned = new AtomicInteger();

    public SpawnSubagentTool(ChatModel chatModel, ToolCallback[] parentTools,
                             String runId, String runKey, String tenantId, String userId, String conversationId,
                             java.util.function.Consumer<ActivityEvent> activitySink,
                             java.util.function.BooleanSupplier stopCheck,
                             com.zwx.zwxagent.agent.observability.TokenUsageService tokenUsage,
                             com.zwx.zwxagent.agent.observability.ModelIoService modelIo,
                             com.zwx.zwxagent.agent.execution.ToolConfirmationService confirmationService,
                             int maxTotalPerRun) {
        this.chatModel = chatModel;
        this.parentTools = parentTools;
        this.runId = runId;
        this.runKey = runKey;
        this.tenantId = tenantId;
        this.userId = userId;
        this.conversationId = conversationId;
        this.activitySink = activitySink;
        this.stopCheck = stopCheck;
        this.tokenUsage = tokenUsage;
        this.modelIo = modelIo;
        this.confirmationService = confirmationService;
        this.maxTotalPerRun = maxTotalPerRun;
    }

    @Override
    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description("""
                        派生一个独立上下文的子代理去完成一个自包含的子任务，返回它的最终结论。
                        使用时机：需要并行处理多个互不依赖的子任务（一次回复中发多个本工具调用），
                        或某个子任务会产生大量中间产物、只需要结论回到主对话时。
                        不要用于：简单查找（自己直接做更快）、彼此依赖的线性步骤（自己按顺序做）。
                        task 必须自包含：子代理看不到我们的对话，把目标、材料、完成判据全部写进去。
                        """)
                .inputSchema(TOOL_SCHEMA)
                .build();
    }

    @Override
    public String call(String input) {
        String task = extractTask(input);
        if (task == null || task.isBlank()) {
            return "spawn 失败：task 不能为空。请给出自包含的子任务描述。";
        }
        int total = spawned.incrementAndGet();
        if (total > maxTotalPerRun) {
            spawned.decrementAndGet();
            return "spawn 失败：本次任务派生子代理数已达上限（" + maxTotalPerRun
                    + "）。请把剩余子任务合并，或自己直接完成。";
        }
        int index = total;
        String workerKey = "subagent-" + index;
        ActivityEvent startEvent = ActivityEvent.of("work", "子代理", "派生子代理 #" + index
                + "：" + compact(task, 80), workerKey);
        streamEvent(startEvent);

        SubagentWorker worker = new SubagentWorker(toolsWithoutSpawn(), task);
        worker.setName(workerKey);
        worker.setSystemPrompt(SUBAGENT_PROMPT);
        worker.setNextStepPrompt("开始执行分配给你的子任务，需要时调用工具。");
        worker.setMaxSteps(MAX_STEPS);
        worker.setChatClient(ChatClient.builder(chatModel)
                .defaultAdvisors(new com.zwx.zwxagent.agent.advisor.MyLoggerAdvisor(),
                        new com.zwx.zwxagent.agent.advisor.TokenUsageAdvisor("subagent-" + index,
                                tokenUsage, () -> runId, modelIo))
                .build());
        if (tenantId != null) {
            worker.setRunIdentity(new AgentRunIdentity(tenantId, userId, "super", conversationId));
        }
        if (runId != null && !runId.isBlank()) {
            worker.setHookRunId(runId);
        }
        Workers.registerWorker(runKey, worker);
        if (stopCheck.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            worker.stopForClientDisconnect();
        }
        long startedAt = System.currentTimeMillis();
        try {
            ToolCallWorkerContext.set(workerKey);
            worker.run(taskPrompt(task));
            worker.flushThinkBuffer();
        } catch (HookAbortException exception) {
            streamEvent(ActivityEvent.of("work", "子代理", "子代理 #" + index + " 被安全策略终止", workerKey));
            return "子代理被安全策略终止：" + exception.getMessage();
        } catch (Exception exception) {
            log.warn("子代理执行失败；runId={}, worker={}：{}", runId, workerKey, exception.getMessage());
            streamEvent(ActivityEvent.of("work", "子代理", "子代理 #" + index + " 执行失败", workerKey));
            return "子代理执行失败：" + exception.getMessage() + "。你可以自己直接完成该子任务。";
        } finally {
            ToolCallWorkerContext.clear();
            Workers.unregisterWorker(runKey, worker);
        }
        String conclusion = worker.lastAssistantText();
        String answer = conclusion.isBlank()
                ? "子代理未产出结论（执行了 " + worker.getCurrentStep() + " 步）。请根据实际情况重试或自己完成。"
                : compact(conclusion, RESULT_MAX_CHARS);
        streamEvent(ActivityEvent.of("work", "子代理",
                "子代理 #" + index + " 完成（" + (System.currentTimeMillis() - startedAt) / 1000 + " 秒）："
                        + compact(answer, 120), workerKey));
        return answer;
    }

    private static final String SUBAGENT_PROMPT = """
            你是主智能体派生的子代理，独立完成分配给你的子任务。
            1. 专注完成子任务，不要展开范围；需要工具时直接调用。
            2. 你的最终回复会被原样返回给主智能体：结论自包含、只保留关键证据与必要说明、控制在 2000 字以内，
               禁止描述你的执行过程或工作汇报。
            3. 资料未支持的事实明确说无法确认，不得编造。用中文。
            """;

    private String taskPrompt(String task) {
        return "【子任务】\n" + task;
    }

    /** 子代理工具集 = 主循环工具集去掉 spawnSubagent（深度=1）。 */
    private ToolCallback[] toolsWithoutSpawn() {
        List<ToolCallback> kept = new java.util.ArrayList<>();
        for (ToolCallback callback : parentTools) {
            if (!TOOL_NAME.equals(callback.getToolDefinition().name())) {
                kept.add(callback);
            }
        }
        return kept.toArray(new ToolCallback[0]);
    }

    /** 从模型给的参数 JSON 里提取 task（宽松解析，兼容代码块包裹）。 */
    private String extractTask(String input) {
        if (input == null) return null;
        String text = input.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return text;
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(text.substring(start, end + 1));
            return node.path("task").asText(null);
        } catch (Exception exception) {
            return text;
        }
    }

    private void streamEvent(ActivityEvent event) {
        if (activitySink == null) return;
        try {
            activitySink.accept(event);
        } catch (RuntimeException ignored) {
            // 实时推送失败不影响子代理
        }
    }

    private static String compact(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 子代理：与主循环同构的 ReAct 执行单元（工具执行 → 语义事件），附思考缓冲冲刷与结论读取。 */
    private static class SubagentWorker extends ToolCallAgent {

        final ThinkDeltaCoalescer thinkCoalescer;

        SubagentWorker(ToolCallback[] tools, String answerSinkUnused) {
            super(tools);
            // 子代理的思考文本不进主对话（主循环只消费结论），合流器目标为空操作
            this.thinkCoalescer = new ThinkDeltaCoalescer(text -> { });
            setThinkTextSink(thinkCoalescer);
        }

        void flushThinkBuffer() {
            thinkCoalescer.flush();
        }

        String lastAssistantText() {
            List<org.springframework.ai.chat.messages.AssistantMessage> messages = getMessageList().stream()
                    .filter(message -> message instanceof org.springframework.ai.chat.messages.AssistantMessage)
                    .map(message -> (org.springframework.ai.chat.messages.AssistantMessage) message)
                    .toList();
            for (int i = messages.size() - 1; i >= 0; i--) {
                String text = messages.get(i).getText();
                if (text != null && !text.isBlank()) return text;
            }
            return "";
        }

        @Override
        public String step() {
            String result;
            ToolCallWorkerContext.set(getName());
            try {
                result = super.step();
            } finally {
                ToolCallWorkerContext.clear();
            }
            return result;
        }
    }
}
