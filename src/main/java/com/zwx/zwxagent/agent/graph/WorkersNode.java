package com.zwx.zwxagent.agent.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.zwx.zwxagent.agent.BaseAgent;
import com.zwx.zwxagent.agent.ToolCallAgent;
import com.zwx.zwxagent.advisor.MyLoggerAdvisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * 执行节点：按计划并行运行多个子 agent（复用加固过的 ToolCallAgent 循环），
 * 每个子 agent 只看到其角色允许的工具子集。质检不通过时带着反馈重跑。
 * 除内部留痕（ACTIVITIES）外，还产出面向用户的语义化事件（SSE_EVENTS）。
 */
@Slf4j
public class WorkersNode implements NodeAction {

    private static final int WORKER_MAX_STEPS = 8;
    private static final int MAX_ACTIVITY_CHARS = 4_000;
    private static final int MAX_EVENT_SUMMARY_CHARS = 120;

    private final ChatModel chatModel;
    private final Executor workerExecutor;

    /** 当前运行中尚未结束的子 agent，供客户端断开时联动停止。 */
    private final List<ToolCallAgent> activeWorkers = new CopyOnWriteArrayList<>();

    /** 逐步采集工具调用的子 agent：每次 step 后把工具执行转成语义事件。 */
    private static class GraphWorker extends ToolCallAgent {
        final WorkerRole role;
        final String title;
        final List<ActivityEvent> events = new ArrayList<>();

        GraphWorker(ToolCallback[] tools, WorkerRole role, String title) {
            super(tools);
            this.role = role;
            this.title = title;
        }

        @Override
        public String step() {
            String result = super.step();
            for (BaseAgent.ToolExecution execution : lastToolExecutions()) {
                events.add(ActivityEvent.of("work", role.displayName(),
                        WorkerRole.toolLabel(execution.name()) + " → " + compact(execution.result(), MAX_EVENT_SUMMARY_CHARS)));
            }
            return result;
        }
    }

    public WorkersNode(ChatModel chatModel, Executor workerExecutor) {
        this.chatModel = chatModel;
        this.workerExecutor = workerExecutor;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        RunContext context = state.value(GraphState.RUN_CONTEXT, RunContext.class).orElse(null);
        List<PlanStep> plan = state.value(GraphState.PLAN)
                .map(value -> (List<PlanStep>) value)
                .orElse(List.of());
        String feedback = state.value(GraphState.VERIFICATION_FEEDBACK, "");
        BooleanSupplier stopCheck = context == null ? () -> false : context.stopCheck();
        ToolCallback[] tools = context == null ? new ToolCallback[0] : context.tools();
        if (context != null && stopCheck.getAsBoolean()) {
            return Map.of(GraphState.ACTIVITIES, List.of("收到停止信号，跳过剩余子任务"));
        }
        String verificationFeedback;
        if (feedback != null && !feedback.isBlank()) {
            verificationFeedback = "上一轮结果未通过质检，请重点修正以下问题：" + feedback;
        } else {
            verificationFeedback = "";
        }
        ChatClient sharedClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        List<ActivityEvent> events = new ArrayList<>();
        List<CompletableFuture<WorkerOutcome>> futures = new ArrayList<>();
        for (int index = 0; index < plan.size(); index++) {
            PlanStep step = plan.get(index);
            final int stepNumber = index + 1;
            events.add(ActivityEvent.of("work", step.role().displayName(),
                    "开始执行（" + stepNumber + "/" + plan.size() + "）：" + step.title()));
            futures.add(CompletableFuture.supplyAsync(() ->
                    runWorker(step, stepNumber, tools, verificationFeedback, sharedClient, stopCheck), workerExecutor));
        }
        List<String> activities = new ArrayList<>();
        List<String> results = new ArrayList<>();
        for (CompletableFuture<WorkerOutcome> future : futures) {
            try {
                WorkerOutcome outcome = future.join();
                activities.addAll(outcome.activities());
                results.add(outcome.summary());
                events.addAll(outcome.events());
            } catch (Exception exception) {
                log.warn("worker 执行失败：{}", exception.getMessage());
                activities.add("子任务执行失败：" + exception.getMessage());
                results.add("子任务失败：" + exception.getMessage());
                events.add(ActivityEvent.of("work", "执行器", "子任务执行失败：" + compact(exception.getMessage(), 80)));
            }
        }
        return Map.of(GraphState.TASK_RESULTS, results,
                GraphState.ACTIVITIES, activities,
                GraphState.SSE_EVENTS, events);
    }

    private WorkerOutcome runWorker(PlanStep step, int stepNumber, ToolCallback[] allTools,
                                    String feedback, ChatClient sharedClient, BooleanSupplier stopCheck) {
        WorkerRole role = step.role();
        ToolCallback[] roleTools = filterTools(allTools, role);
        GraphWorker worker = new GraphWorker(roleTools, role, step.title());
        worker.setName("worker-" + stepNumber + "-" + role.key());
        worker.setSystemPrompt("""
                你是多智能体团队中的%s。专注完成分配给你的子任务，不要展开任务范围。
                使用工具时保持克制：能一步完成就不要多步；拿不到的信息明确说明，不要编造。
                完成子任务后，不再调用工具，直接用中文输出你的结论与关键产出。%s
                """.formatted(role.description(),
                role == WorkerRole.AUTHOR ? " 生成的文件务必保存到工作区，并在结论里说明文件名。" : ""));
        worker.setNextStepPrompt(feedback.isBlank()
                ? "开始执行你的子任务，需要时调用工具。"
                : feedback + "\n开始执行你的子任务，需要时调用工具。");
        worker.setMaxSteps(WORKER_MAX_STEPS);
        worker.setChatClient(sharedClient);
        activeWorkers.add(worker);
        try {
            String taskPrompt = "子任务标题：" + step.title() + "\n子任务要求：" + step.detail();
            log.info("启动 {}：{}", worker.getName(), step.title());
            String runLog = worker.run(taskPrompt);
            String summary = lastAssistantText(worker).isBlank()
                    ? compact(runLog)
                    : lastAssistantText(worker);
            List<String> activities = new ArrayList<>();
            for (String line : runLog.split("\n")) {
                if (line.isBlank()) continue;
                activities.add("[worker" + stepNumber + "/" + role.key() + "] " + compact(line));
            }
            activities.add("[worker" + stepNumber + "/" + role.key() + "] 结论：" + compact(summary));
            List<ActivityEvent> events = new ArrayList<>(worker.events);
            events.add(ActivityEvent.of("work", role.displayName(),
                    "完成：" + compact(summary, MAX_EVENT_SUMMARY_CHARS)));
            return new WorkerOutcome(activities, events, "【" + step.title() + "】" + summary);
        } finally {
            activeWorkers.remove(worker);
        }
    }

    private String lastAssistantText(ToolCallAgent worker) {
        List<AssistantMessage> messages = worker.getMessageList().stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .toList();
        return messages.isEmpty() ? "" : messages.getLast().getText();
    }

    /** 按角色过滤工具；general 返回全部。 */
    static ToolCallback[] filterTools(ToolCallback[] tools, WorkerRole role) {
        Set<String> allowed = role.allowedTools();
        if (allowed == null) return tools;
        return Arrays.stream(tools)
                .filter(callback -> {
                    try {
                        return callback.getToolDefinition() != null
                                && allowed.contains(callback.getToolDefinition().name().toLowerCase(Locale.ROOT));
                    } catch (Exception ignored) {
                        return false;
                    }
                })
                .toArray(ToolCallback[]::new);
    }

    void stopAll() {
        activeWorkers.forEach(ToolCallAgent::stopForClientDisconnect);
    }

    private static String compact(String value) {
        return compact(value, MAX_ACTIVITY_CHARS);
    }

    private static String compact(String value, int maxChars) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "...[截断]";
    }

    private record WorkerOutcome(List<String> activities, List<ActivityEvent> events, String summary) {
    }
}
