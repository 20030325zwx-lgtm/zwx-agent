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
import com.zwx.zwxagent.agent.BaseAgent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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

    private static final long MAX_RUN_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private final ChatModel chatModel;
    private final Executor workerExecutor;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private CompiledGraph compiledGraph;
    private WorkersNode workersNode;

    public ManusGraphOrchestrator(ChatModel dashscopeChatModel,
                                  @org.springframework.beans.factory.annotation.Qualifier("graphWorkerExecutor")
                                  Executor workerExecutor) {
        this.chatModel = dashscopeChatModel;
        this.workerExecutor = workerExecutor;
    }

    @PostConstruct
    void init() throws Exception {
        workersNode = new WorkersNode(chatModel, workerExecutor);
        StateGraph graph = new StateGraph(this::newState);
        graph.addNode(NODE_PLANNER, AsyncNodeAction.node_async(new PlannerNode(chatModel)));
        graph.addNode(NODE_WORKERS, AsyncNodeAction.node_async(workersNode));
        graph.addNode(NODE_VERIFIER, AsyncNodeAction.node_async(new VerifierNode(chatModel)));
        graph.addNode(NODE_AGGREGATOR, AsyncNodeAction.node_async(new AggregatorNode(chatModel)));
        graph.addEdge(StateGraph.START, NODE_PLANNER);
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
        compiledGraph = graph.compile(CompileConfig.builder().saverConfig(saverConfig).build());
        compiledGraph.setMaxIterations(16);
        log.info("manus 多智能体图编译完成：{}",
                compiledGraph.getGraph(GraphRepresentation.Type.MERMAID).content());
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
                Map.entry(GraphState.RUN_CONTEXT, new ReplaceStrategy())));
        return state;
    }

    /** 质检后的路由：要求返工且回 workers，否则交付。 */
    static String route(String verificationStatus) {
        return "revise".equals(verificationStatus) ? NODE_WORKERS : NODE_AGGREGATOR;
    }

    private String routeAfterVerifier(OverAllState state) throws Exception {
        return route(state.value(GraphState.VERIFICATION_STATUS, "pass"));
    }

    public record ManusRunRequest(String conversationId,
                                  String message,
                                  String historyContext,
                                  ToolCallback[] tools,
                                  String knowledgeContext,
                                  Consumer<BaseAgent.RunResult> completionHandler) {
    }

    /**
     * 与旧 BaseAgent.runStream 对齐：activity 事件承载过程，默认消息承载最终回答，[DONE] 收尾。
     */
    public SseEmitter runStream(ManusRunRequest request, Executor runExecutor) {
        SseEmitter emitter = new SseEmitter(MAX_RUN_MILLIS + TimeUnit.SECONDS.toMillis(5));
        AtomicBoolean stopped = new AtomicBoolean(false);
        CompletableFuture.runAsync(() -> execute(request, emitter, stopped), runExecutor);
        Runnable stop = () -> {
            stopped.set(true);
            workersNode.stopAll();
        };
        emitter.onTimeout(() -> {
            stop.run();
            log.warn("manus graph SSE connection timeout");
        });
        emitter.onError(error -> stop.run());
        return emitter;
    }

    private void execute(ManusRunRequest request, SseEmitter emitter, AtomicBoolean stopped) {
        StringBuilder answer = new StringBuilder();
        List<String> activities = new ArrayList<>();
        try {
            RunContext context = RunContext.of(request.tools(), request.knowledgeContext(), stopped::get);
            Map<String, Object> inputs = Map.of(
                    GraphState.INPUT, request.message(),
                    GraphState.HISTORY, request.historyContext() == null ? "" : request.historyContext(),
                    GraphState.RUN_CONTEXT, context);
            int sentActivities = 0;
            int sentEvents = 0;
            RunnableConfig runnableConfig = RunnableConfig.builder()
                    .threadId(request.conversationId() == null ? java.util.UUID.randomUUID().toString()
                            : request.conversationId())
                    .build();
            for (NodeOutput output : compiledGraph.stream(inputs, runnableConfig)) {
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
                    answer.append(state.value(GraphState.FINAL_ANSWER, ""));
                }
            }
            if (answer.isEmpty()) {
                answer.append(stopped.get()
                        ? "本次任务已停止：客户端连接中断或达到时限，已完成的工作见上方活动记录。"
                        : "未能生成本次任务的最终回答，请调整请求后重试。");
            }
            request.completionHandler().accept(new BaseAgent.RunResult(answer.toString(), List.copyOf(activities)));
            emitter.send(answer.toString());
            emitter.send("[DONE]");
            emitter.complete();
        } catch (Exception exception) {
            log.error("manus graph 执行失败", exception);
            if (!answer.isEmpty()) {
                try {
                    request.completionHandler().accept(new BaseAgent.RunResult(answer.toString(), List.copyOf(activities)));
                } catch (Exception persistenceError) {
                    log.error("failed to persist partial graph answer", persistenceError);
                }
            }
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

    /** 返回编译图的 Mermaid 表示（调试与文档用）。 */
    public String mermaidDiagram() {
        return compiledGraph.getGraph(GraphRepresentation.Type.MERMAID).content();
    }
}
