package com.zwx.zwxagent.agent.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.zwx.zwxagent.agent.advisor.MyLoggerAdvisor;
import com.zwx.zwxagent.agent.advisor.TokenUsageAdvisor;
import com.zwx.zwxagent.agent.core.AgentRunIdentity;
import com.zwx.zwxagent.agent.core.BaseAgent;
import com.zwx.zwxagent.agent.core.ToolCallAgent;
import com.zwx.zwxagent.agent.execution.ToolConfirmationService;
import com.zwx.zwxagent.capability.tools.ToolCallWorkerContext;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单循环档（直答/简单工作任务）：一个主循环 ReActAgent 直接对用户负责，替代
 * planner→workers→verifier→aggregator 固定四阶段（Anthropic"反对过早多 agent 化"的落地方案）。
 * <ul>
 *   <li>思考文本（含工具调用前的叙述）经 thinkTextSink → answerChunkSink 实时流式给用户——
 *       叙述即回答，工具执行穿插其间（Claude Code 式体验）</li>
 *   <li>工具确认挂起与图编排档同语义：输出 PENDING_CONFIRMATION，恢复时重跑本节点，
 *       已成功工具由幂等账本回放</li>
 *   <li>worker 注册表与 WorkersNode 共享（ACTIVE_WORKERS），停止请求覆盖两种拓扑</li>
 * </ul>
 */
@Slf4j
public class DirectNode implements NodeAction {

    /** 主循环步数上限：现在承担规划+执行+交付三职，比原 worker 的 8 步放宽。 */
    static final int MAIN_LOOP_MAX_STEPS = 14;

    static final String SYSTEM_PROMPT = """
            你是 Aster 智能体，直接对用户负责，独立完成用户的请求。
            1. 需要工具时直接调用；调用前用一两句面向用户的话说明你要做什么，拿到结果后给出结论。
            2. 输出是对用户的回答：直接、自然、克制，禁止"团队/流程/步骤汇报"式的元话语，禁止复述内部活动记录。
            3. 有私有知识证据时保留对应的 [文件名#切片号] 引用；资料未支持的事实明确说无法确认，不得推断补充。
            4. spawnSubagent 工具的使用纪律：
               - 用于并行处理多个互不依赖的子任务（在一条回复里同时发多个调用），或某子任务会产生
                 大量中间产物、只需结论回到对话时；
               - 不要用于简单查找（自己直接做更快）和彼此依赖的线性步骤（自己按顺序做）；
               - task 必须自包含（子代理看不到本对话），写清目标、材料与完成判据；
               - 拿到各子代理结论后再综合，禁止把子代理的过程描述原样转述给用户。
            5. 用中文回答。
            """;

    private final ChatClient chatClient;
    private final ToolConfirmationService confirmationService;

    /** 计量归属的 run：节点实例按 run 创建，apply 时从图状态绑定。 */
    private final AtomicReference<String> tokenUsageRunId = new AtomicReference<>("");

    public DirectNode(ChatModel chatModel, com.zwx.zwxagent.agent.observability.TokenUsageService tokenUsage,
                      ToolConfirmationService confirmationService,
                      com.zwx.zwxagent.agent.observability.ModelIoService modelIo) {
        this.confirmationService = confirmationService;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new MyLoggerAdvisor(),
                        new TokenUsageAdvisor("direct", tokenUsage, tokenUsageRunId::get, modelIo))
                .build();
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        tokenUsageRunId.set(state.value(GraphState.RUN_ID, ""));
        RunContext context = state.value(GraphState.RUN_CONTEXT, RunContext.class).orElse(null);
        String input = state.value(GraphState.INPUT, "");
        String historyContext = state.value(GraphState.HISTORY, "");
        String runId = state.value(GraphState.RUN_ID, "");
        String runKey = context == null || context.conversationId() == null
                ? java.util.UUID.randomUUID().toString() : context.conversationId();
        ToolCallback[] tools = context == null ? new ToolCallback[0] : context.tools();
        BooleanSupplier stopCheck = context == null ? () -> false : context.stopCheck();
        Consumer<ActivityEvent> activitySink = context == null ? null : context.activitySink();
        if (context != null && stopCheck.getAsBoolean()) {
            return Map.of(GraphState.ACTIVITIES, List.of("收到停止信号，跳过执行"));
        }
        String knowledge = context == null ? "" : context.knowledgeContext();
        boolean webSearchEnabled = context == null || context.webSearchEnabled();

        String workerKey = "direct-1-general";
        List<ActivityEvent> events = new ArrayList<>();
        ActivityEvent startEvent = ActivityEvent.of("work", "智能体", "开始处理你的请求", workerKey);
        events.add(startEvent);
        streamEvent(activitySink, startEvent);

        StringBuilder streamedAnswer = new StringBuilder();
        DirectWorker worker = new DirectWorker(tools, workerKey, activitySink,
                text -> {
                    streamedAnswer.append(text);
                    if (context != null && context.answerChunkSink() != null) {
                        context.answerChunkSink().accept(text);
                    }
                });
        worker.setName(workerKey);
        worker.setSystemPrompt(SYSTEM_PROMPT);
        worker.setNextStepPrompt("开始处理用户的请求，需要时调用工具。");
        worker.setMaxSteps(MAIN_LOOP_MAX_STEPS);
        worker.setChatClient(chatClient);
        worker.setToolCallingManager(new com.zwx.zwxagent.capability.tools.ParallelizingToolCallingManager());
        if (context != null && context.tenantId() != null) {
            worker.setRunIdentity(new AgentRunIdentity(context.tenantId(), context.userId(),
                    "super", context.conversationId()));
        }
        if (runId != null && !runId.isBlank()) {
            worker.setHookRunId(runId);
        }
        Workers.registerWorker(runKey, worker);
        if (stopCheck.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            worker.stopForClientDisconnect();
        }
        List<String> activities = new ArrayList<>();
        String taskPrompt = (historyContext == null || historyContext.isBlank() ? ""
                : "【近期对话上下文】\n" + historyContext + "\n\n")
                + (knowledge.isBlank() ? "" : Workers.knowledgePrompt(knowledge)) + input;
        try {
            ToolCallWorkerContext.set(workerKey);
            try {
                log.info("主循环启动：{}", compact(input, 80));
                worker.run(taskPrompt);
            } finally {
                ToolCallWorkerContext.clear();
            }
        } finally {
            // 思考增量的残余缓冲冲刷：保证流式文本完整送达
            worker.flushThinkBuffer();
            Workers.unregisterWorker(runKey, worker);
        }
        for (BaseAgent.ToolExecution execution : worker.lastToolExecutionsSnapshot()) {
            ActivityEvent event = ActivityEvent.of("work", "智能体",
                    "使用工具：" + execution.name(), workerKey);
            events.add(event);
            streamEvent(activitySink, event);
        }
        String answer = streamedAnswer.toString().isBlank()
                ? worker.lastAssistantText() : streamedAnswer.toString();
        if (answer.isBlank()) {
            answer = "未能生成回答，请重试或换一种说法。";
        }
        activities.add("单循环完成：" + compact(answer, 120));
        ActivityEvent doneEvent = ActivityEvent.of("work", "智能体", "完成", workerKey);
        events.add(doneEvent);
        streamEvent(activitySink, doneEvent);

        Map<String, Object> output = new java.util.HashMap<>();
        output.put(GraphState.FINAL_ANSWER, answer);
        output.put(GraphState.TASK_RESULTS, List.of("【直接完成】" + compact(answer, 600)));
        output.put(GraphState.ACTIVITIES, activities);
        output.put(GraphState.SSE_EVENTS, events);
        if (confirmationPending(runId)) {
            output.put(GraphState.PENDING_CONFIRMATION, confirmationService.pendingOf(runId).stream()
                    .map(ToolConfirmationService.PendingConfirmation::toolName).toList());
        }
        return output;
    }

    private boolean confirmationPending(String runId) {
        return confirmationService != null && runId != null && !runId.isBlank()
                && confirmationService.isRunRegistered(runId)
                && !confirmationService.pendingOf(runId).isEmpty();
    }

    /**
     * 单循环 worker：与图编排的 GraphWorker 同构（工具执行→语义事件），另含
     * 思考文本直通（thinkTextSink）与最终回答读取。
     */
    private static class DirectWorker extends ToolCallAgent {

        final String workerKey;
        final List<ActivityEvent> events = new ArrayList<>();
        final java.util.function.Consumer<ActivityEvent> activitySink;
        final ThinkDeltaCoalescer thinkCoalescer;
        final List<BaseAgent.ToolExecution> toolExecutions = java.util.Collections.synchronizedList(new ArrayList<>());

        DirectWorker(ToolCallback[] tools, String workerKey, java.util.function.Consumer<ActivityEvent> activitySink,
                     java.util.function.Consumer<String> answerChunkSink) {
            super(tools);
            this.workerKey = workerKey;
            this.activitySink = activitySink;
            this.thinkCoalescer = new ThinkDeltaCoalescer(answerChunkSink);
            setThinkTextSink(thinkCoalescer);
        }

        void flushThinkBuffer() {
            thinkCoalescer.flush();
        }

        List<BaseAgent.ToolExecution> lastToolExecutionsSnapshot() {
            return List.copyOf(toolExecutions);
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
            ToolCallWorkerContext.set(workerKey);
            try {
                result = super.step();
            } finally {
                ToolCallWorkerContext.clear();
            }
            List<BaseAgent.ToolExecution> executions = lastToolExecutions();
            toolExecutions.addAll(executions);
            for (BaseAgent.ToolExecution execution : executions) {
                ActivityEvent event = ActivityEvent.of("work", "智能体",
                        "使用工具：" + execution.name() + " → " + compact(execution.result(), 120), workerKey);
                events.add(event);
                streamEvent(activitySink, event);
            }
            return result;
        }
    }

    private static void streamEvent(java.util.function.Consumer<ActivityEvent> sink, ActivityEvent event) {
        if (sink == null) return;
        try {
            sink.accept(event);
        } catch (RuntimeException ignored) {
            // 实时推送失败不能中断 worker
        }
    }

    private static String compact(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
