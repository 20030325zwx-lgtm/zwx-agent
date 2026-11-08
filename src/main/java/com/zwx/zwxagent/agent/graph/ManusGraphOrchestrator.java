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
    public static final String NODE_DIRECT = "direct";

    /** 单 run 派生子代理总数上限（防失控闸之一；单 step 并行度由模型自决、执行池有界）。 */
    static final int SPAWN_MAX_TOTAL_PER_RUN = 12;
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

    /** worker 思考阶段流式开关：关闭后 thinkDeltaSink 不注入，worker 走阻塞模型调用。 */
    @org.springframework.beans.factory.annotation.Value("${app.agent.worker-think-stream:true}")
    private boolean workerThinkStream = true;

    private final ChatModel chatModel;
    private final Executor workerExecutor;
    private final AgentRunService agentRunService;
    private final AgentToolCallLedgerService toolCallLedger;
    private final com.zwx.zwxagent.agent.observability.TokenUsageService tokenUsage;
    private final com.zwx.zwxagent.agent.execution.ToolConfirmationService toolConfirmationService;
    private final com.zwx.zwxagent.capability.tools.PermissionService permissionService;
    private final com.zwx.zwxagent.agent.observability.ModelIoService modelIo;
    private final com.zwx.zwxagent.agent.observability.AgentMetrics metrics;
    private final com.zwx.zwxagent.agent.execution.PromptSource promptSource;
    private final com.zwx.zwxagent.agent.execution.store.AgentRunCheckpointStore checkpointStore;
    private final com.zwx.zwxagent.agent.execution.store.RunEventLog runEventLog;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    /** v2 追加式快照开关：开启后每次节点完成同时写 agent_run_checkpoint（权威）与 agent_run 行内列（展示缓存）。 */
    @org.springframework.beans.factory.annotation.Value("${app.agent.checkpoint.v2:true}")
    private boolean checkpointV2 = true;

    /** 单循环档开关（DirectNode）：关闭后所有工作任务回到固定图编排。 */
    @org.springframework.beans.factory.annotation.Value("${app.agent.single-loop.enabled:true}")
    private boolean singleLoopEnabled = true;

    private CompiledGraph compiledGraph;
    private CompiledGraph compiledDirectGraph;

    private record GraphInstance(CompiledGraph graph) {
    }

    public ManusGraphOrchestrator(ChatModel dashscopeChatModel,
                                  @org.springframework.beans.factory.annotation.Qualifier("graphWorkerExecutor")
                                  Executor workerExecutor) {
        this(dashscopeChatModel, workerExecutor, null, null, null, null, null, null, null, null, null, null);
    }

    @Autowired
    public ManusGraphOrchestrator(ChatModel dashscopeChatModel,
                                  @org.springframework.beans.factory.annotation.Qualifier("graphWorkerExecutor")
                                  Executor workerExecutor,
                                  AgentRunService agentRunService,
                                  AgentToolCallLedgerService toolCallLedger,
                                  com.zwx.zwxagent.agent.observability.TokenUsageService tokenUsage,
                                  com.zwx.zwxagent.agent.execution.ToolConfirmationService toolConfirmationService,
                                  com.zwx.zwxagent.capability.tools.PermissionService permissionService,
                                  com.zwx.zwxagent.agent.observability.ModelIoService modelIo,
                                  com.zwx.zwxagent.agent.observability.AgentMetrics metrics,
                                  com.zwx.zwxagent.agent.execution.PromptSource promptSource,
                                  com.zwx.zwxagent.agent.execution.store.AgentRunCheckpointStore checkpointStore,
                                  com.zwx.zwxagent.agent.execution.store.RunEventLog runEventLog) {
        this.chatModel = dashscopeChatModel;
        this.workerExecutor = workerExecutor;
        this.agentRunService = agentRunService;
        this.toolCallLedger = toolCallLedger;
        this.tokenUsage = tokenUsage;
        this.toolConfirmationService = toolConfirmationService;
        this.permissionService = permissionService;
        this.modelIo = modelIo;
        this.metrics = metrics;
        this.promptSource = promptSource;
        this.checkpointStore = checkpointStore;
        this.runEventLog = runEventLog;
    }

    @PostConstruct
    void init() throws Exception {
        log.info("manus run checkpoint store: {}", agentRunService == null ? "disabled" : "enabled");
        GraphInstance sample = buildGraph(chatModel);
        compiledGraph = sample.graph();
        compiledDirectGraph = compiledGraph;
        log.info("manus 主循环图编译完成（main-loop + spawnSubagent）：{}",
                compiledGraph.getGraph(GraphRepresentation.Type.MERMAID).content());
    }

    private GraphInstance buildGraph() throws Exception {
        return buildGraph(chatModel);
    }

    /**
     * 主循环拓扑（终态，2026-11-08）：START → direct → END。
     * DirectNode 内部即完整 ReAct 主循环 + spawnSubagent 工具（子代理按需派生）；
     * 编排层（planner/workers/verifier/aggregator/plan-gate）已退役删除。
     */
    private GraphInstance buildGraph(ChatModel graphChatModel) throws Exception {
        StateGraph graph = new StateGraph(this::newState);
        graph.addNode(NODE_DIRECT, AsyncNodeAction.node_async(new DirectNode(graphChatModel, tokenUsage,
                toolConfirmationService, modelIo)));
        graph.addEdge(StateGraph.START, NODE_DIRECT);
        graph.addEdge(NODE_DIRECT, StateGraph.END);
        // 必须配置 saver（1.0.0.2 的 stream 路径会无条件读取）；
        // MemorySaver 按引用保存状态（不序列化 RunContext），并按 threadId=conversationId 隔离。
        SaverConfig saverConfig = SaverConfig.builder()
                .type(SaverConstant.MEMORY)
                .register(SaverConstant.MEMORY, new MemorySaver())
                .build();
        CompiledGraph compiled = graph.compile(CompileConfig.builder().saverConfig(saverConfig).build());
        compiled.setMaxIterations(16);
        return new GraphInstance(compiled);
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
                Map.entry(GraphState.PENDING_CONFIRMATION, new ReplaceStrategy()),
                Map.entry(GraphState.PERMISSION_MODE, new ReplaceStrategy()),
                Map.entry(GraphState.ORCHESTRATION, new ReplaceStrategy())));
        return state;
    }

    /** 质检后的路由：要求返工且回 workers，否则交付。 */
    /** 恢复入口：主循环架构下恒为 direct 节点（老 checkpoint 的编排节点名一并归入 default）。 */
    public static String resumeNode(GraphCheckpoint checkpoint) {
        return NODE_DIRECT;
    }

    /** 兼容保留：返工清理语义已随编排层退役，原样返回。 */
    public static GraphCheckpoint prepareResumeCheckpoint(GraphCheckpoint checkpoint, String resumeFrom) {
        return checkpoint;
    }

    public record ManusRunRequest(String conversationId,
                                  String tenantId,
                                  String userId,
                                  ChatModel chatModel,
                                  String message,
                                  String historyContext,
                                  ToolCallback[] tools,
                                  String knowledgeContext,
                                  String ragTraceJson,
                                  String prefaceActivity,
                                  String runId,
                                  String permissionMode,
                                  GraphCheckpoint resumeCheckpoint,
                                  String resumeFrom,
                                  Runnable cleanup,
                                  Consumer<ManusRunResult> completionHandler,
                                  /** v2 派发路径：节点安全点轮询 control_state（暂停/取消），空则仅看本地 stopped。 */
                                  java.util.function.BooleanSupplier controlStopCheck) {
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
                    Workers.stopAll(request.conversationId());
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
        if (permissionService != null && !effectiveRunId.isBlank()) {
            permissionService.registerRun(effectiveRunId,
                    com.zwx.zwxagent.capability.tools.PermissionMode.parse(request.permissionMode()),
                    request.tenantId(), request.userId());
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
            if (request.resumeCheckpoint() != null) {
                // 恢复前修尾：日志里未闭合的工具调用合成 repair 事件（真相层），并对用户可见
                if (runEventLog != null && !effectiveRunId.isBlank()) {
                    try {
                        List<String> repairs = runEventLog.repairUnclosedToolCalls(
                                effectiveRunId, request.tenantId(), request.userId());
                        for (String note : repairs) {
                            stream.send("activity", objectMapper.writeValueAsString(
                                    ActivityEvent.of("work", "系统", "恢复检查：" + note)));
                        }
                    } catch (Exception exception) {
                        log.warn("恢复修尾失败（不影响续跑）；runId={}", effectiveRunId, exception);
                    }
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
            // worker 思考增量：合流后经 think-delta 事件实时可见（填平干活阶段的空白期）
            Consumer<ThinkDeltaEvent> thinkDeltaSink = workerThinkStream ? event -> {
                if (event == null || event.text() == null || event.text().isEmpty()) return;
                try {
                    log.debug("[think-delta] runId={}, worker={}, chars={}", request.runId(),
                            event.workerKey(), event.text().length());
                    stream.send("think-delta", objectMapper.writeValueAsString(event));
                } catch (Exception exception) {
                    log.debug("思考增量推送失败；runId={}", request.runId(), exception);
                }
            } : null;
            ToolCallback[] wrappedTools = IdempotentToolCallback.wrapAll(request.runId(), request.tools(),
                    toolCallLedger, id -> stream.send("tool-call", String.valueOf(id)));
            java.util.function.BooleanSupplier safeStop = request.controlStopCheck() == null
                    ? stopped::get
                    : () -> stopped.get() || request.controlStopCheck().getAsBoolean();
            // 子代理工具（深度=1：内部工具集为 wrappedTools 去 spawn）；本工具再入账本包装
            SpawnSubagentTool spawnRaw = new SpawnSubagentTool(
                    request.chatModel() == null ? chatModel : request.chatModel(),
                    wrappedTools, effectiveRunId, request.conversationId(),
                    request.tenantId(), request.userId(), request.conversationId(),
                    activitySink, safeStop, tokenUsage, modelIo, toolConfirmationService,
                    SPAWN_MAX_TOTAL_PER_RUN);
            ToolCallback[] tools = new ToolCallback[wrappedTools.length + 1];
            System.arraycopy(wrappedTools, 0, tools, 0, wrappedTools.length);
            tools[wrappedTools.length] = IdempotentToolCallback.wrapAll(request.runId(),
                    new ToolCallback[]{spawnRaw}, toolCallLedger,
                    id -> stream.send("tool-call", String.valueOf(id)))[0];
            RunContext context = RunContext.of(tools, request.knowledgeContext(), safeStop,
                    request.tenantId(), request.userId(), request.conversationId(), chunk -> {
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
            if (thinkDeltaSink != null) context = context.withThinkDeltaSink(thinkDeltaSink);
            if (tokenUsage != null) {
                // 私有知识块规模：同一段会被每个 worker 与交付节点各带一份，是复制型成本的乘数
                tokenUsage.bindContext(request.runId(),
                        request.knowledgeContext() == null ? 0 : request.knowledgeContext().length());
            }
            Map<String, Object> inputs = Map.of(
                    GraphState.INPUT, request.message(),
                    GraphState.HISTORY, request.historyContext() == null ? "" : request.historyContext(),
                    GraphState.RUN_CONTEXT, context,
                    GraphState.RUN_ID, request.runId() == null ? "" : request.runId(),
                    GraphState.PERMISSION_MODE,
                    com.zwx.zwxagent.capability.tools.PermissionMode.parse(request.permissionMode()).name());
            int sentActivities = 0;
            int sentEvents = request.resumeCheckpoint() == null ? 0 : request.resumeCheckpoint().events().size();
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
                if (NODE_DIRECT.equals(output.node())) {
                    finalAnswer = state.value(GraphState.FINAL_ANSWER, "");
                }
                String completedNode = completedNode(state);
                if (completedNode != null) {
                    persistCheckpoint(request, completedNode, state, streamedAnswer);
                }
            }
            boolean pendingConfirmation = pendingConfirmationOf(effectiveRunId);
            boolean completed = !stopped.get() && !pendingConfirmation
                    && finalAnswer != null && !finalAnswer.isBlank();
            String answer = completed ? finalAnswer : completedAnswer(finalAnswer, streamedAnswer, stopped.get());
            String status = completed ? "COMPLETED" : "INTERRUPTED";
            if (pendingConfirmation) {
                publishPendingConfirmation(stream, effectiveRunId);
                if (metrics != null) metrics.runPausedForConfirmation();
            }
            if (metrics != null) metrics.runFinished(status);
            persist(request, answer, activities, completed, status, null, finalized);
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
            if (permissionService != null) permissionService.releaseRun(effectiveRunId);
            if (modelIo != null) modelIo.releaseRun(effectiveRunId);
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
        // 活动与事件差量续推：恢复从 checkpoint 记录的位置继续，不重复推历史事件
        saved.put(GraphState.ACTIVITIES, checkpoint.activities());
        saved.put(GraphState.SSE_EVENTS, checkpoint.events());
        if (checkpoint.finalAnswer() != null && !checkpoint.finalAnswer().isBlank()) {
            saved.put(GraphState.FINAL_ANSWER, checkpoint.finalAnswer());
        }
        saved.put(GraphState.RUN_CONTEXT, context);
        saved.put(GraphState.RUN_ID, request.runId() == null ? "" : request.runId());
        saved.put(GraphState.PERMISSION_MODE,
                com.zwx.zwxagent.capability.tools.PermissionMode.parse(request.permissionMode()).name());
        return saved;
    }


    /**
     * 图库的 NodeOutput 使用共享状态引用，且输出标签与“刚完成的节点”并不总是一一对应。
     * 这里根据状态里已经出现的结果判断最后完成的真实节点，避免 checkpoint 落后一个节点。
     */
    /** 检查点归属：主循环架构下唯一执行节点是 direct。 */
    static String completedNode(OverAllState state) {
        return NODE_DIRECT;
    }

    private void persistCheckpoint(ManusRunRequest request, String node, OverAllState state, StringBuilder streamedAnswer) {
        if (agentRunService == null || request.runId() == null || request.runId().isBlank()) return;
        // 等待工具确认的 run 不落节点检查点：最后一个检查点保持在挂起前，
        // 确认后的 resume 从主循环重跑（已成功的工具调用由幂等账本回放，不会重复副作用）。
        if (pendingConfirmationOf(request.runId())) return;
        try {
            List<ActivityEvent> events = state.value(GraphState.SSE_EVENTS)
                    .map(value -> (List<ActivityEvent>) value).orElse(List.of());
            List<String> activities = state.value(GraphState.ACTIVITIES)
                    .map(value -> (List<String>) value).orElse(List.of());
            GraphCheckpoint checkpoint = new GraphCheckpoint(
                    node,
                    NODE_DIRECT,
                    List.of(),
                    List.of(),
                    activities,
                    events,
                    0,
                    "",
                    "",
                    state.value(GraphState.FINAL_ANSWER, ""),
                    streamedAnswer.toString(),
                    List.of());
            // v2 追加式快照（真相层）：先落权威，失败仅降级为行内列（日志可重建，不阻断主流程）
            if (checkpointV2 && checkpointStore != null) {
                long lastEventSeq = runEventLog == null ? 0 : runEventLog.lastSeq(
                        request.runId(), request.tenantId(), request.userId());
                checkpointStore.appendGraphCheckpoint(request.runId(), request.tenantId(), request.userId(),
                        checkpoint, lastEventSeq, "AFTER_STEP");
            }
            // agent_run 行内列（展示缓存）：列表/详情页直读，避免每次恢复都重放快照
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
                + (event.summary() == null ? "" : event.summary()) + "|"
                + (event.workerKey() == null ? "" : event.workerKey());
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
