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
import com.zwx.zwxagent.execution.AgentRunService;
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
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    static final String NODE_RESUME_ROUTER = "resume_router";

    private static final long MAX_RUN_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private final ChatModel chatModel;
    private final Executor workerExecutor;
    private final AgentRunService agentRunService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private CompiledGraph compiledGraph;

    private record GraphInstance(CompiledGraph graph, WorkersNode workers) {
    }

    public ManusGraphOrchestrator(ChatModel dashscopeChatModel,
                                  @org.springframework.beans.factory.annotation.Qualifier("graphWorkerExecutor")
                                  Executor workerExecutor) {
        this(dashscopeChatModel, workerExecutor, null);
    }

    @Autowired
    public ManusGraphOrchestrator(ChatModel dashscopeChatModel,
                                  @org.springframework.beans.factory.annotation.Qualifier("graphWorkerExecutor")
                                  Executor workerExecutor,
                                  AgentRunService agentRunService) {
        this.chatModel = dashscopeChatModel;
        this.workerExecutor = workerExecutor;
        this.agentRunService = agentRunService;
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
        WorkersNode workersNode = new WorkersNode(chatModel, workerExecutor);
        StateGraph graph = new StateGraph(this::newState);
        graph.addNode(NODE_RESUME_ROUTER, AsyncNodeAction.node_async(state -> Map.of()));
        graph.addNode(NODE_PLANNER, AsyncNodeAction.node_async(new PlannerNode(chatModel)));
        graph.addNode(NODE_WORKERS, AsyncNodeAction.node_async(workersNode));
        graph.addNode(NODE_VERIFIER, AsyncNodeAction.node_async(new VerifierNode(chatModel)));
        graph.addNode(NODE_AGGREGATOR, AsyncNodeAction.node_async(new AggregatorNode(chatModel)));
        graph.addEdge(StateGraph.START, NODE_RESUME_ROUTER);
        graph.addConditionalEdges(NODE_RESUME_ROUTER, AsyncEdgeAction.edge_async(this::routeResume),
                Map.of(NODE_PLANNER, NODE_PLANNER, NODE_WORKERS, NODE_WORKERS,
                        NODE_VERIFIER, NODE_VERIFIER, NODE_AGGREGATOR, NODE_AGGREGATOR));
        graph.addEdge(NODE_PLANNER, NODE_WORKERS);
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
                Map.entry(GraphState.FINAL_ANSWER, new ReplaceStrategy()),
                Map.entry(GraphState.RUN_CONTEXT, new ReplaceStrategy()),
                Map.entry(GraphState.RESUME_FROM, new ReplaceStrategy()),
                Map.entry(GraphState.RUN_ID, new ReplaceStrategy())));
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
            case NODE_WORKERS -> NODE_VERIFIER;
            case NODE_VERIFIER -> route(verificationStatus);
            case NODE_AGGREGATOR -> StateGraph.END;
            default -> NODE_PLANNER;
        };
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
                                  String message,
                                  String historyContext,
                                  ToolCallback[] tools,
                                  String knowledgeContext,
                                  String ragTraceJson,
                                  String runId,
                                  GraphCheckpoint resumeCheckpoint,
                                  String resumeFrom,
                                  Consumer<ManusRunResult> completionHandler) {
    }

    public record ManusRunResult(String answer, List<String> activities, boolean completed,
                                 String status, String error) {
    }

    /**
     * 与旧 BaseAgent.runStream 对齐：activity 事件承载过程，默认消息承载最终回答，[DONE] 收尾。
     */
    public SseEmitter runStream(ManusRunRequest request, Executor runExecutor) {
        SseEmitter emitter = new SseEmitter(MAX_RUN_MILLIS + TimeUnit.SECONDS.toMillis(5));
        AtomicBoolean stopped = new AtomicBoolean(false);
        GraphInstance runGraph;
        try {
            runGraph = buildGraph();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to build agent graph", exception);
        }
        FutureTask<Void> runTask = new FutureTask<>(() -> {
            execute(request, emitter, stopped, runGraph.graph());
            return null;
        });
        Runnable stop = () -> {
            stopped.set(true);
            runTask.cancel(true);
            runGraph.workers().stopAll(request.conversationId());
        };
        runExecutor.execute(runTask);
        emitter.onTimeout(() -> {
            stop.run();
            log.warn("manus graph SSE connection timeout");
        });
        emitter.onError(error -> stop.run());
        return emitter;
    }

    private void execute(ManusRunRequest request, SseEmitter emitter, AtomicBoolean stopped, CompiledGraph graph) {
        StringBuilder streamedAnswer = new StringBuilder();
        List<String> activities = new ArrayList<>();
        AtomicBoolean finalized = new AtomicBoolean(false);
        String finalAnswer = "";
        try {
            if (request.ragTraceJson() != null && !request.ragTraceJson().isBlank()) {
                emitter.send(SseEmitter.event().name("rag-trace").data(request.ragTraceJson()));
            }
            if (request.runId() != null && !request.runId().isBlank()) {
                emitter.send(SseEmitter.event().name("run").data(request.runId()));
            }
            long[] lastPartialFlush = {0};
            RunContext context = RunContext.of(request.tools(), request.knowledgeContext(), stopped::get,
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
                            try {
                                emitter.send(chunk);
                            } catch (java.io.IOException exception) {
                                stopped.set(true);
                                throw new IllegalStateException("Unable to stream answer chunk", exception);
                            }
                        }
                    });
            Map<String, Object> inputs = Map.of(
                    GraphState.INPUT, request.message(),
                    GraphState.HISTORY, request.historyContext() == null ? "" : request.historyContext(),
                    GraphState.RUN_CONTEXT, context,
                    GraphState.RUN_ID, request.runId() == null ? "" : request.runId());
            int sentActivities = 0;
            int sentEvents = 0;
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
                    emitter.send(SseEmitter.event().name("activity")
                            .data(objectMapper.writeValueAsString(events.get(sentEvents++))));
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
                String completedNode = completedNode(state);
                if (completedNode != null) {
                    persistCheckpoint(request, completedNode, state, streamedAnswer);
                }
            }
            boolean completed = !stopped.get() && finalAnswer != null && !finalAnswer.isBlank();
            String answer = completed ? finalAnswer : completedAnswer(finalAnswer, streamedAnswer, stopped.get());
            String status = completed ? "COMPLETED" : "INTERRUPTED";
            persist(request, answer, activities, completed, status, null, finalized);
            sendDone(emitter, completed);
        } catch (Exception exception) {
            log.error("manus graph 执行失败", exception);
            String answer = completedAnswer(finalAnswer, streamedAnswer, stopped.get());
            String status = stopped.get() ? "INTERRUPTED" : "FAILED";
            persist(request, answer, activities, false, status, exception.getMessage(), finalized);
            try {
                emitter.send(SseEmitter.event().name("generation-error")
                        .data(com.zwx.zwxagent.util.ErrorMessages.describe(exception)));
                emitter.send("任务已中止：" + com.zwx.zwxagent.util.ErrorMessages.describe(exception));
                emitter.send("[DONE]");
                emitter.complete();
            } catch (Exception sendError) {
                emitter.completeWithError(sendError);
            }
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
        if (checkpoint.verificationStatus() != null && !checkpoint.verificationStatus().isBlank()) {
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
                    streamedAnswer.toString());
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

    private void sendDone(SseEmitter emitter, boolean completed) {
        try {
            if (!completed) {
                emitter.send(SseEmitter.event().name("interrupted").data("回答已中断，已保留部分内容"));
            }
            emitter.send("[DONE]");
            emitter.complete();
        } catch (Exception sendError) {
            emitter.completeWithError(sendError);
        }
    }

    /** 返回编译图的 Mermaid 表示（调试与文档用）。 */
    public String mermaidDiagram() {
        return compiledGraph.getGraph(GraphRepresentation.Type.MERMAID).content();
    }
}
