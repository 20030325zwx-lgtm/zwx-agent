package com.zwx.zwxagent.agent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.constant.SaverConstant;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.zwx.zwxagent.agent.execution.AgentRunService;
import com.zwx.zwxagent.agent.execution.AgentRunView;
import com.zwx.zwxagent.agent.execution.AgentToolCallLedgerService;
import com.zwx.zwxagent.capability.tools.IdempotentToolCallback;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 多智能体图编排器：planner → workers（并行）→ verifier →（revise 回 workers）→ aggregator。
 * 对外暴露与旧 BaseAgent.runStream 一致的 SSE 契约（activity 事件 + 最终回答 + [DONE]）。
 */
@Slf4j
@Service
public class ManusGraphOrchestrator {

    static final String NODE_PLANNER = "planner";
    static final String NODE_WORKERS = "workers";
    static final String NODE_VERIFIER = "verifier";
    static final String NODE_AGGREGATOR = "aggregator";
    /** 计划确认门：多步计划生成后暂停，等用户授权再执行（Plan 先对齐后执行）。 */
    static final String NODE_PLAN_GATE = "plan_gate";
    static final String NODE_RESUME_ROUTER = "resume_router";

    private static final long MAX_RUN_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final ScheduledExecutorService RECONNECT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "manus-sse-retention");
                thread.setDaemon(true);
                return thread;
            });

    private final ConcurrentHashMap<String, RunStream> runStreams = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Value("${app.agent.sse-reconnect-grace-seconds:30}")
    private long reconnectGraceSeconds = 30;

    @org.springframework.beans.factory.annotation.Value("${app.agent.sse-history-max-events:5000}")
    private int sseHistoryMaxEvents = 5000;

    @org.springframework.beans.factory.annotation.Value("${app.agent.sse-replay-retention-seconds:120}")
    private long replayRetentionSeconds = 120;

    private final ChatModel chatModel;
    private final Executor workerExecutor;
    private final AgentRunService agentRunService;
    private final AgentToolCallLedgerService toolCallLedger;
    private final com.zwx.zwxagent.agent.observability.TokenUsageService tokenUsage;
    private final com.zwx.zwxagent.agent.execution.ToolConfirmationService toolConfirmationService;
    private final com.zwx.zwxagent.agent.observability.AgentMetrics metrics;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private CompiledGraph compiledGraph;

    private record GraphInstance(CompiledGraph graph, WorkersNode workers) {
    }

    public ManusGraphOrchestrator(ChatModel dashscopeChatModel,
                                  @org.springframework.beans.factory.annotation.Qualifier("graphWorkerExecutor")
                                  Executor workerExecutor) {
        this(dashscopeChatModel, workerExecutor, null, null, null, null, null);
    }

    @Autowired
    public ManusGraphOrchestrator(ChatModel dashscopeChatModel,
                                  @org.springframework.beans.factory.annotation.Qualifier("graphWorkerExecutor")
                                  Executor workerExecutor,
                                  AgentRunService agentRunService,
                                  AgentToolCallLedgerService toolCallLedger,
                                  com.zwx.zwxagent.agent.observability.TokenUsageService tokenUsage,
                                  com.zwx.zwxagent.agent.execution.ToolConfirmationService toolConfirmationService,
                                  com.zwx.zwxagent.agent.observability.AgentMetrics metrics) {
        this.chatModel = dashscopeChatModel;
        this.workerExecutor = workerExecutor;
        this.agentRunService = agentRunService;
        this.toolCallLedger = toolCallLedger;
        this.tokenUsage = tokenUsage;
        this.toolConfirmationService = toolConfirmationService;
        this.metrics = metrics;
    }

    @PostConstruct
    void init() throws Exception {
        log.info("manus run checkpoint store: {}", agentRunService == null ? "disabled" : "enabled");
        GraphInstance sample = buildGraph();
        compiledGraph = sample.graph();
        log.info("manus 多智能体图编译完成：{}",
                compiledGraph.getGraph(GraphRepresentation.Type.MERMAID).content());
    }

    private GraphInstance buildGraph() throws Exception {
        return buildGraph(chatModel);
    }

    private GraphInstance buildGraph(ChatModel graphChatModel) throws Exception {
        WorkersNode workersNode = new WorkersNode(graphChatModel, workerExecutor, tokenUsage, toolConfirmationService);
        StateGraph graph = new StateGraph(this::newState);
        graph.addNode(NODE_RESUME_ROUTER, AsyncNodeAction.node_async(state -> Map.of()));
        graph.addNode(NODE_PLANNER, AsyncNodeAction.node_async(new PlannerNode(graphChatModel, tokenUsage)));
        graph.addNode(NODE_WORKERS, AsyncNodeAction.node_async(workersNode));
        graph.addNode(NODE_VERIFIER, AsyncNodeAction.node_async(new VerifierNode(graphChatModel, tokenUsage)));
        graph.addNode(NODE_AGGREGATOR, AsyncNodeAction.node_async(new AggregatorNode(graphChatModel, tokenUsage)));
        graph.addNode(NODE_PLAN_GATE, AsyncNodeAction.node_async(state -> Map.of()));
        graph.addEdge(StateGraph.START, NODE_RESUME_ROUTER);
        graph.addConditionalEdges(NODE_RESUME_ROUTER, AsyncEdgeAction.edge_async(this::routeResume),
                java.util.Map.of(NODE_PLANNER, NODE_PLANNER, NODE_WORKERS, NODE_WORKERS,
                        NODE_AGGREGATOR, NODE_AGGREGATOR, StateGraph.END, StateGraph.END));
        graph.addConditionalEdges(NODE_PLANNER, AsyncEdgeAction.edge_async(this::routeAfterPlanner),
                java.util.Map.of(NODE_WORKERS, NODE_WORKERS, NODE_PLAN_GATE, NODE_PLAN_GATE));
        graph.addEdge(NODE_PLAN_GATE, StateGraph.END);
        graph.addEdge(NODE_WORKERS, NODE_VERIFIER);
        graph.addConditionalEdges(NODE_VERIFIER, AsyncEdgeAction.edge_async(this::routeAfterVerifier),
                Map.of(NODE_WORKERS, NODE_WORKERS, NODE_AGGREGATOR, NODE_AGGREGATOR));
        graph.addEdge(NODE_AGGREGATOR, StateGraph.END);
        // 必须配置 saver（1.0.0.2 的 stream 路径会无条件读取）；
        // MemorySaver 按引用保存状态（不序列化 RunContext），并按 threadId=conversationId 隔离，
        // 为后续断线恢复（getStateHistory/resume）打底。
        SaverConfig saverConfig = SaverConfig.builder()
                .type(SaverConstant.MEMORY)
                .register(SaverConstant.MEMORY, new MemorySaver())
                .build();
        CompiledGraph compiled = graph.compile(CompileConfig.builder().saverConfig(saverConfig).build());
        compiled.setMaxIterations(16);
        return new GraphInstance(compiled, workersNode);
    }

    private OverAllState newState() {
        OverAllState state = new OverAllState();
        state.registerKeyAndStrategy(Map.ofEntries(
                Map.entry(GraphState.INPUT, new ReplaceStrategy()),
                Map.entry(GraphState.HISTORY, new ReplaceStrategy()),
                Map.entry(GraphState.PLAN, new ReplaceStrategy()),
                Map.entry(GraphState.TASK_RESULTS, new AppendStrategy()),
                Map.entry(GraphState.ACTIVITIES, new AppendStrategy()),
                Map.entry(GraphState.SSE_EVENTS, new AppendStrategy()),
                Map.entry(GraphState.REVISION, new ReplaceStrategy()),
                Map.entry(GraphState.VERIFICATION_STATUS, new ReplaceStrategy()),
                Map.entry(GraphState.VERIFICATION_FEEDBACK, new ReplaceStrategy()),
                Map.entry(GraphState.REVISE_STEPS, new ReplaceStrategy()),
                Map.entry(GraphState.FINAL_ANSWER, new ReplaceStrategy()),
                Map.entry(GraphState.RUN_CONTEXT, new ReplaceStrategy()),
                Map.entry(GraphState.RESUME_FROM, new ReplaceStrategy()),
                Map.entry(GraphState.RUN_ID, new ReplaceStrategy()),
                Map.entry(GraphState.PENDING_CONFIRMATION, new ReplaceStrategy())));
        return state;
    }

    /** 质检后的路由：要求返工且回 workers，否则交付。 */
    static String route(String verificationStatus) {
        return "revise".equals(verificationStatus) ? NODE_WORKERS : NODE_AGGREGATOR;
    }

    private String routeAfterVerifier(OverAllState state) throws Exception {
        return route(state.value(GraphState.VERIFICATION_STATUS, "unverified"));
    }

    private String routeResume(OverAllState state) {
        return routeResumeFrom(state.value(GraphState.RESUME_FROM, NODE_PLANNER));
    }

    static String routeResumeFrom(String resumeFrom) {
        return switch (resumeFrom == null ? "" : resumeFrom) {
            case NODE_WORKERS -> NODE_WORKERS;
            case NODE_VERIFIER -> NODE_VERIFIER;
            case NODE_AGGREGATOR -> NODE_AGGREGATOR;
            default -> NODE_PLANNER;
        };
    }

    static String nextNode(String node, String verificationStatus) {
        return switch (node == null ? "" : node) {
            case NODE_PLANNER -> NODE_WORKERS;
            case NODE_PLAN_GATE -> StateGraph.END;
            case NODE_WORKERS -> NODE_VERIFIER;
            case NODE_VERIFIER -> route(verificationStatus);
            case NODE_AGGREGATOR -> StateGraph.END;
            default -> NODE_PLANNER;
        };
    }

    /** 计划确认门：多步计划先挂起等待用户授权；单步任务直接执行。 */
    static String planGateTarget(int planSize) {
        return planSize > 1 ? NODE_PLAN_GATE : NODE_WORKERS;
    }

    private String routeAfterPlanner(OverAllState state) throws Exception {
        List<PlanStep> plan = state.value(GraphState.PLAN)
                .map(value -> (List<PlanStep>) value)
                .orElse(List.of());
        return planGateTarget(plan.size());
    }

    /** 根据最后一个已完成节点计算恢复入口；没有检查点时从 planner 重新开始。 */
    public static String resumeNode(GraphCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.lastNode() == null || checkpoint.lastNode().isBlank()) {
            return NODE_PLANNER;
        }
        // aggregator 已完成但 run 尚未收尾时，重新执行 aggregator 生成最终答案，
        // 而不是把 END 当成恢复入口重新规划。
        if (NODE_AGGREGATOR.equals(checkpoint.lastNode())) return NODE_AGGREGATOR;
        return nextNode(checkpoint.lastNode(), checkpoint.verificationStatus());
    }

    /** 返工恢复时清空上一轮结果，避免 AppendStrategy 把旧结果和新结果拼在一起。 */
    public static GraphCheckpoint prepareResumeCheckpoint(GraphCheckpoint checkpoint, String resumeFrom) {
        if (checkpoint == null) return null;
        if (NODE_WORKERS.equals(resumeFrom)) {
            return checkpoint.withTaskResults(List.of());
        }
        return checkpoint;
    }

    public record ManusRunRequest(String conversationId,
                                  String tenantId,
                                  ChatModel chatModel,
                                  String message,
                                  String historyContext,
                                  ToolCallback[] tools,
                                  String knowledgeContext,
                                  String ragTraceJson,
                                  String prefaceActivity,
                                  String runId,
                                  GraphCheckpoint resumeCheckpoint,
                                  String resumeFrom,
                                  Runnable cleanup,
                                  Consumer<ManusRunResult> completionHandler) {
    }

    public record ManusRunResult(String answer, List<String> activities, boolean completed,
                                 String status, String error) {
    }

    public record ManusRunHandle(SseEmitter emitter, RunStream stream) {
    }

    public record ReconnectResult(SseEmitter emitter, RunStream stream, boolean completed, String error) {
    }

    /**
     * 与旧 BaseAgent.runStream 对齐：activity 事件承载过程，默认消息承载最终回答，[DONE] 收尾。
     */
    public ManusRunHandle runStream(ManusRunRequest request, Executor runExecutor) {
        GraphInstance runGraph;
        try {
            runGraph = buildGraph(request.chatModel() == null ? chatModel : request.chatModel());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to build agent graph", exception);
        }
        String runId = request.runId() == null || request.runId().isBlank()
                ? java.util.UUID.randomUUID().toString() : request.runId();
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean cancelledBeforeStart = new AtomicBoolean(false);
        Object startLock = new Object();
        RunStream[] holder = new RunStream[1];
        ScheduledFuture<?>[] deadline = new ScheduledFuture<?>[1];
        FutureTask<Void> runTask = new FutureTask<>(() -> {
            synchronized (startLock) {
                if (cancelledBeforeStart.get()) {
                    // 任务在被调度前已被用户停止：释放租约与工具资源，避免会话长时间 409。
                    if (holder[0] != null) holder[0].cleanup();
                    scheduleStreamRemoval(runId, holder[0]);
                    return null;
                }
                started.set(true);
            }
            try {
                execute(request, holder[0], stopped, runGraph.graph());
            } finally {
                if (deadline[0] != null) deadline[0].cancel(false);
                holder[0].cleanup();
                scheduleStreamRemoval(runId, holder[0]);
            }
            return null;
        });
        RunStream stream = new RunStream(runId, sseHistoryMaxEvents, reconnectGraceSeconds, RECONNECT_SCHEDULER,
                () -> {
                    stopped.set(true);
                    synchronized (startLock) {
                        if (!started.get()) cancelledBeforeStart.set(true);
                    }
                    runTask.cancel(true);
                    if (cancelledBeforeStart.get()) {
                        if (holder[0] != null) holder[0].cleanup();
                        scheduleStreamRemoval(runId, holder[0]);
                    }
                    runGraph.workers().stopAll(request.conversationId());
                },
                request.cleanup());
        holder[0] = stream;
        runStreams.put(runId, stream);
        SseEmitter emitter = stream.createEmitter();
        deadline[0] = RECONNECT_SCHEDULER.schedule(() -> {
            if (!stream.completed()) {
                log.warn("manus run 超过 {} 分钟上限，主动停止；runId={}", MAX_RUN_MILLIS / 60000, runId);
                stream.send("activity", "{\"phase\":\"verify\",\"agent\":\"系统\",\"summary\":\"任务超过执行时限，已自动停止\"}");
                stream.cancel();
            }
        }, MAX_RUN_MILLIS, TimeUnit.MILLISECONDS);
        try {
            runExecutor.execute(runTask);
        } catch (RuntimeException exception) {
            if (deadline[0] != null) deadline[0].cancel(false);
            runStreams.remove(runId, stream);
            stream.cleanup();
            throw exception;
        }
        return new ManusRunHandle(emitter, stream);
    }

    /** 用户主动停止当前实例上的 run；run 已结束或不在本实例时返回 false。 */
    public boolean cancel(String runId) {
        RunStream stream = runStreams.get(runId);
        return stream != null && stream.cancel();
    }

    /** 客户端重连：重放 lastEventId 之后的事件，并恢复后续实时推送。 */
    public ReconnectResult reconnect(String runId, String lastEventId) {
        RunStream stream = runStreams.get(runId);
        if (stream == null) return new ReconnectResult(null, null, false, "not-live");
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(6));
        if (!stream.attach(emitter, RunStream.parseSequence(lastEventId))) {
            return new ReconnectResult(null, stream, false, "history-gap");
        }
        return new ReconnectResult(emitter, stream, stream.completed(), null);
    }

    private void scheduleStreamRemoval(String runId, RunStream stream) {
        RECONNECT_SCHEDULER.schedule(() -> runStreams.remove(runId, stream),
                Math.max(1, replayRetentionSeconds), TimeUnit.SECONDS);
    }

    private void execute(ManusRunRequest request, RunStream stream, AtomicBoolean stopped, CompiledGraph graph) {
        StringBuilder streamedAnswer = new StringBuilder();
        List<String> activities = new ArrayList<>();
        AtomicBoolean finalized = new AtomicBoolean(false);
        String finalAnswer = "";
        String effectiveRunId = request.runId() == null ? "" : request.runId();
        if (toolConfirmationService != null && !effectiveRunId.isBlank()) {
            toolConfirmationService.registerRun(effectiveRunId);
        }
        try {
            if (request.ragTraceJson() != null && !request.ragTraceJson().isBlank()) {
                stream.send("rag-trace", request.ragTraceJson());
            }
            if (request.runId() != null && !request.runId().isBlank()) {
                stream.send("run", request.runId());
            }
            if (request.prefaceActivity() != null && !request.prefaceActivity().isBlank()) {
                // 图启动前的附加事件（当前为意图识别观察结论）；随 run 事件一起先于图输出到达前端。
                stream.send("activity", request.prefaceActivity());
            }
            List<AgentRunView.PlanStepView> sentPlan = List.of();
            if (request.resumeCheckpoint() != null) {
                List<AgentRunView.PlanStepView> resumedPlan = AgentRunView.planView(request.resumeCheckpoint().plan());
                if (!resumedPlan.isEmpty()) {
                    stream.send("plan", objectMapper.writeValueAsString(resumedPlan));
                    sentPlan = resumedPlan;
                }
            }
            long[] lastPartialFlush = {0};
            boolean webSearchEnabled = hasTool(request.tools(), "searchWeb");
            Map<String, AtomicInteger> liveEvents = new ConcurrentHashMap<>();
            Consumer<ActivityEvent> activitySink = event -> {
                if (event == null) return;
                liveEvents.computeIfAbsent(eventKey(event), key -> new AtomicInteger()).incrementAndGet();
                try {
                    stream.send("activity", objectMapper.writeValueAsString(event));
                } catch (Exception exception) {
                    log.debug("实时活动事件推送失败；runId={}", request.runId(), exception);
                }
            };
            ToolCallback[] tools = IdempotentToolCallback.wrapAll(request.runId(), request.tools(), toolCallLedger);
            RunContext context = RunContext.of(tools, request.knowledgeContext(), stopped::get,
                    request.tenantId(), request.conversationId(), chunk -> {
                        // 先保留已生成内容，再尝试推送；客户端断开后仍能把草稿落库。
                        streamedAnswer.append(chunk);
                        if (agentRunService != null && request.runId() != null && !request.runId().isBlank()) {
                            long now = System.currentTimeMillis();
                            if (now - lastPartialFlush[0] >= 750) {
                                lastPartialFlush[0] = now;
                                agentRunService.updatePartialAnswer(request.runId(), streamedAnswer.toString());
                            }
                        }
                        if (!stopped.get()) {
                            stream.sendMessage(chunk);
                        }
                    }, activitySink, webSearchEnabled);
            if (tokenUsage != null) {
                // 私有知识块规模：同一段会被每个 worker 与交付节点各带一份，是复制型成本的乘数
                tokenUsage.bindContext(request.runId(),
                        request.knowledgeContext() == null ? 0 : request.knowledgeContext().length());
            }
            Map<String, Object> inputs = Map.of(
                    GraphState.INPUT, request.message(),
                    GraphState.HISTORY, request.historyContext() == null ? "" : request.historyContext(),
                    GraphState.RUN_CONTEXT, context,
                    GraphState.RUN_ID, request.runId() == null ? "" : request.runId());
            int sentActivities = 0;
            int sentEvents = request.resumeCheckpoint() == null ? 0 : request.resumeCheckpoint().events().size();
            boolean planGated = false;
            List<AgentRunView.PlanStepView> gatedPlan = List.of();
            RunnableConfig runnableConfig = RunnableConfig.builder()
                    .threadId(request.runId() == null || request.runId().isBlank()
                            ? java.util.UUID.randomUUID().toString()
                            : request.runId() + (request.resumeFrom() == null ? "" : ":resume:" + java.util.UUID.randomUUID()))
                    .build();
            var outputs = request.resumeCheckpoint() == null
                    ? graph.stream(inputs, runnableConfig)
                    : graph.stream(resumeInputs(request, context), runnableConfig);
            for (NodeOutput output : outputs) {
                if (stopped.get()) break;
                OverAllState state = output.state();
                // 计划板：planner 产出后推送一次结构化计划，恢复时以 checkpoint 为准。
                List<PlanStep> plan = state.value(GraphState.PLAN)
                        .map(value -> (List<PlanStep>) value)
                        .orElse(List.of());
                List<AgentRunView.PlanStepView> planView = AgentRunView.planView(plan);
                if (!planView.isEmpty() && !planView.equals(sentPlan)) {
                    stream.send("plan", objectMapper.writeValueAsString(planView));
                    sentPlan = planView;
                }
                // 面向用户的语义事件：差量推送 JSON（前端按 phase/agent/summary 渲染）
                List<ActivityEvent> events = state.value(GraphState.SSE_EVENTS)
                        .map(value -> (List<ActivityEvent>) value)
                        .orElse(List.of());
                while (sentEvents < events.size()) {
                    ActivityEvent event = events.get(sentEvents++);
                    if (consumeLiveSent(liveEvents, event)) continue;
                    stream.send("activity", objectMapper.writeValueAsString(event));
                }
                // 内部留痕（附件提取依赖），不再直接推给前端
                List<String> latest = state.value(GraphState.ACTIVITIES)
                        .map(value -> (List<String>) value)
                        .orElse(List.of());
                while (sentActivities < latest.size()) {
                    activities.add(latest.get(sentActivities++));
                }
                if (NODE_AGGREGATOR.equals(output.node())) {
                    finalAnswer = state.value(GraphState.FINAL_ANSWER, "");
                }
                if (NODE_PLAN_GATE.equals(output.node())) {
                    planGated = true;
                    gatedPlan = planView;
                }
                String completedNode = completedNode(state);
                if (completedNode != null) {
                    persistCheckpoint(request, completedNode, state, streamedAnswer);
                }
            }
            boolean pendingConfirmation = pendingConfirmationOf(effectiveRunId);
            boolean completed = !stopped.get() && !pendingConfirmation && !planGated
                    && finalAnswer != null && !finalAnswer.isBlank();
            String answer = completed ? finalAnswer : completedAnswer(finalAnswer, streamedAnswer, stopped.get());
            if (planGated) {
                answer = "已拆解出 " + gatedPlan.size() + " 步执行计划（含依赖顺序），请在工作区确认后开始执行；"
                        + "直接回复修改意见也可以，我会重新规划。";
            }
            String status = completed ? "COMPLETED" : "INTERRUPTED";
            if (pendingConfirmation) {
                publishPendingConfirmation(stream, effectiveRunId);
                if (metrics != null) metrics.runPausedForConfirmation();
            }
            if (metrics != null) metrics.runFinished(status);
            persist(request, answer, activities, completed, status, null, finalized);
            if (planGated) publishPlanProposal(stream, effectiveRunId, gatedPlan);
            publishTokenUsage(stream, request.runId());
            sendDone(stream, completed);
        } catch (Exception exception) {
            log.error("manus graph 执行失败", exception);
            String answer = completedAnswer(finalAnswer, streamedAnswer, stopped.get());
            String status = stopped.get() ? "INTERRUPTED" : "FAILED";
            if (metrics != null) metrics.runFinished(status);
            persist(request, answer, activities, false, status, exception.getMessage(), finalized);
            publishTokenUsage(stream, request.runId());
            stream.send("generation-error", com.zwx.zwxagent.common.ErrorMessages.describe(exception));
            stream.sendMessage("任务已中止：" + com.zwx.zwxagent.common.ErrorMessages.describe(exception));
            stream.complete();
        } finally {
            if (toolConfirmationService != null) toolConfirmationService.releaseRun(effectiveRunId);
        }
    }

    /** 计划确认挂起：推送 plan-proposed 事件并把 verification_status 标记为 plan_pending，授权后走既有 resume 链路。 */
    private void publishPlanProposal(RunStream stream, String runId, List<AgentRunView.PlanStepView> plan) {
        try {
            stream.send("plan-proposed", objectMapper.writeValueAsString(plan));
            if (agentRunService != null) agentRunService.markPlanPending(runId);
        } catch (Exception exception) {
            log.warn("计划确认事件发布失败；runId={}", runId, exception);
        }
    }

    private boolean pendingConfirmationOf(String runId) {
        return toolConfirmationService != null && runId != null && !runId.isBlank()
                && toolConfirmationService.isRunRegistered(runId)
                && !toolConfirmationService.pendingOf(runId).isEmpty();
    }

    /** 挂起收尾：推送 confirmation-required 事件并把待确认清单落库，前端弹确认框，决定后走既有 resume 链路。 */
    private void publishPendingConfirmation(RunStream stream, String runId) {
        try {
            List<Map<String, String>> confirmations = toolConfirmationService.pendingOf(runId).stream()
                    .map(pending -> Map.of("tool", pending.toolName(),
                            "argumentsSummary", pending.argumentsSummary() == null ? "" : pending.argumentsSummary()))
                    .toList();
            String json = objectMapper.writeValueAsString(java.util.Map.of("runId", runId, "confirmations", confirmations));
            stream.send("confirmation-required", json);
            if (agentRunService != null) agentRunService.markPendingConfirmation(runId, json);
        } catch (Exception exception) {
            log.warn("待确认清单发布失败；runId={}", runId, exception);
        }
    }

    Map<String, Object> resumeInputs(ManusRunRequest request, RunContext context) {
        GraphCheckpoint checkpoint = request.resumeCheckpoint();
        Map<String, Object> saved = new java.util.HashMap<>();
        saved.put(GraphState.INPUT, request.message());
        saved.put(GraphState.HISTORY, request.historyContext() == null ? "" : request.historyContext());
        saved.put(GraphState.PLAN, checkpoint.plan());
        saved.put(GraphState.TASK_RESULTS, checkpoint.taskResults());
        saved.put(GraphState.ACTIVITIES, checkpoint.activities());
        saved.put(GraphState.SSE_EVENTS, checkpoint.events());
        saved.put(GraphState.REVISION, checkpoint.revision());
        if (!checkpoint.reviseSteps().isEmpty()) {
            saved.put(GraphState.REVISE_STEPS, checkpoint.reviseSteps());
        }
        // plan_pending 只是计划确认门的挂起标记：授权续跑后不应再带入图状态
        if (checkpoint.verificationStatus() != null && !checkpoint.verificationStatus().isBlank()
                && !"plan_pending".equals(checkpoint.verificationStatus())) {
            saved.put(GraphState.VERIFICATION_STATUS, checkpoint.verificationStatus());
        }
        if (checkpoint.verificationFeedback() != null && !checkpoint.verificationFeedback().isBlank()) {
            saved.put(GraphState.VERIFICATION_FEEDBACK, checkpoint.verificationFeedback());
        }
        if (checkpoint.finalAnswer() != null && !checkpoint.finalAnswer().isBlank()) {
            saved.put(GraphState.FINAL_ANSWER, checkpoint.finalAnswer());
        }
        saved.put(GraphState.RUN_CONTEXT, context);
        saved.put(GraphState.RESUME_FROM, request.resumeFrom());
        saved.put(GraphState.RUN_ID, request.runId() == null ? "" : request.runId());
        return saved;
    }


    /**
     * 图库的 NodeOutput 使用共享状态引用，且输出标签与“刚完成的节点”并不总是一一对应。
     * 这里根据状态里已经出现的结果判断最后完成的真实节点，避免 checkpoint 落后一个节点。
     */
    @SuppressWarnings("unchecked")
    static String completedNode(OverAllState state) {
        String finalAnswer = state.value(GraphState.FINAL_ANSWER, "");
        if (finalAnswer != null && !finalAnswer.isBlank()) return NODE_AGGREGATOR;
        String verification = state.value(GraphState.VERIFICATION_STATUS, "");
        if (verification != null && !verification.isBlank()) return NODE_VERIFIER;
        List<String> results = state.value(GraphState.TASK_RESULTS)
                .map(value -> (List<String>) value).orElse(List.of());
        if (!results.isEmpty()) return NODE_WORKERS;
        List<PlanStep> plan = state.value(GraphState.PLAN)
                .map(value -> (List<PlanStep>) value).orElse(List.of());
        return plan.isEmpty() ? null : NODE_PLANNER;
    }

    @SuppressWarnings("unchecked")
    private void persistCheckpoint(ManusRunRequest request, String node, OverAllState state, StringBuilder streamedAnswer) {
        if (agentRunService == null || request.runId() == null || request.runId().isBlank()) return;
        // 等待工具确认的 run 不落节点检查点：最后一个检查点保持在 planner，
        // 这样确认后的 resume 会从 workers 重跑（已成功的工具调用由幂等账本回放，不会重复副作用）。
        if (pendingConfirmationOf(request.runId())) return;
        try {
            List<PlanStep> plan = state.value(GraphState.PLAN)
                    .map(value -> (List<PlanStep>) value).orElse(List.of());
            List<String> results = state.value(GraphState.TASK_RESULTS)
                    .map(value -> (List<String>) value).orElse(List.of());
            List<String> activities = state.value(GraphState.ACTIVITIES)
                    .map(value -> (List<String>) value).orElse(List.of());
            List<ActivityEvent> events = state.value(GraphState.SSE_EVENTS)
                    .map(value -> (List<ActivityEvent>) value).orElse(List.of());
            String verification = state.value(GraphState.VERIFICATION_STATUS, "");
            List<Integer> reviseSteps = state.value(GraphState.REVISE_STEPS)
                    .map(value -> (List<Integer>) value)
                    .orElse(List.of());
            GraphCheckpoint checkpoint = new GraphCheckpoint(
                    node,
                    nextNode(node, verification),
                    plan,
                    results,
                    activities,
                    events,
                    state.value(GraphState.REVISION, 0),
                    verification,
                    state.value(GraphState.VERIFICATION_FEEDBACK, ""),
                    state.value(GraphState.FINAL_ANSWER, ""),
                    streamedAnswer.toString(),
                    reviseSteps);
            agentRunService.checkpoint(request.runId(), checkpoint);
        } catch (Exception exception) {
            log.warn("persist manus checkpoint failed; runId={}, node={}", request.runId(), node, exception);
        }
    }

    static String completedAnswer(String finalAnswer, StringBuilder streamedAnswer, boolean stopped) {
        if (finalAnswer != null && !finalAnswer.isBlank()) return finalAnswer;
        if (!streamedAnswer.isEmpty()) return streamedAnswer.toString();
        return stopped
                ? "本次回答在完成前被中断，已保留这一轮的提问。可以继续提问，或稍后重试。"
                : "本次任务执行失败，请稍后重试。";
    }

    private void persist(ManusRunRequest request, String answer, List<String> activities, boolean completed,
                         String status, String error, AtomicBoolean finalized) {
        if (!finalized.compareAndSet(false, true)) return;
        try {
            request.completionHandler().accept(new ManusRunResult(
                    answer, List.copyOf(activities), completed, status, error));
        } catch (Exception persistenceError) {
            log.error("failed to persist manus run result; runId={}, status={}", request.runId(), status, persistenceError);
        }
    }

    /**
     * run 收尾发布 token 账目：落库 + 推 usage 事件，随后释放台账。
     * 计量属于观测面，任何失败都不得影响已完成的回答。
     */
    private void publishTokenUsage(RunStream stream, String runId) {
        if (tokenUsage == null || runId == null || runId.isBlank()) return;
        try {
            int prompt = tokenUsage.promptTokens(runId);
            int completion = tokenUsage.completionTokens(runId);
            int calls = tokenUsage.calls(runId);
            if (calls == 0) return;
            String usageJson = tokenUsage.usageJson(runId);
            if (agentRunService != null) {
                agentRunService.recordTokenUsage(runId, usageJson, prompt, completion, calls);
            }
            stream.send("usage", usageJson);
        } catch (Exception exception) {
            log.warn("token 计量发布失败；runId={}", runId, exception);
        } finally {
            tokenUsage.release(runId);
        }
    }

    private static boolean hasTool(ToolCallback[] tools, String name) {
        if (tools == null) return false;
        for (ToolCallback tool : tools) {
            try {
                if (tool.getToolDefinition() != null && name.equalsIgnoreCase(tool.getToolDefinition().name())) return true;
            } catch (RuntimeException ignored) {
                // 某些动态工具无法读取定义，按不存在处理。
            }
        }
        return false;
    }

    private static String eventKey(ActivityEvent event) {
        return (event.phase() == null ? "" : event.phase()) + "|"
                + (event.agent() == null ? "" : event.agent()) + "|"
                + (event.summary() == null ? "" : event.summary());
    }

    private static boolean consumeLiveSent(Map<String, AtomicInteger> liveEvents, ActivityEvent event) {
        String key = eventKey(event);
        AtomicInteger count = liveEvents.get(key);
        if (count == null) return false;
        if (count.decrementAndGet() <= 0) liveEvents.remove(key, count);
        return true;
    }

    private void sendDone(RunStream stream, boolean completed) {
        if (!completed) {
            stream.send("interrupted", "回答已中断，已保留部分内容");
        }
        stream.complete();
    }

    /** 返回编译图的 Mermaid 表示（调试与文档用）。 */
    public String mermaidDiagram() {
        return compiledGraph.getGraph(GraphRepresentation.Type.MERMAID).content();
    }
}
