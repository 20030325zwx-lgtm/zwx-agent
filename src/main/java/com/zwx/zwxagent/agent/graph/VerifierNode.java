package com.zwx.zwxagent.agent.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.zwx.zwxagent.agent.advisor.MyLoggerAdvisor;
import com.zwx.zwxagent.agent.advisor.TokenUsageAdvisor;
import com.zwx.zwxagent.agent.observability.TokenUsageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 质检节点：对照用户请求与计划核对工作者结果，
 * 给出 pass（通过）或 revise（返工 + 反馈）。质检失败按通过处理，避免死循环。
 */
@Slf4j
public class VerifierNode implements NodeAction {

    static final int MAX_REVISIONS = 1;

    private static final String SYSTEM_PROMPT = """
            你是多智能体系统的质检员。对照用户请求与计划，核对各子任务结果：
            1. 结果是否覆盖了用户请求的核心诉求；
            2. 结果之间是否矛盾、是否与请求无关；
            3. 是否存在明显未完成的关键项。
            只输出 JSON，不要输出其他文字：
            {"status":"pass","feedback":""}
            或
            {"status":"revise","feedback":"一句中文，说明缺什么、错什么、如何修正"}
            注意：轻微瑕疵直接放行；只有关键内容缺失或结果跑题才要求返工。
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient chatClient;

    /** 计量归属的 run：节点实例按 run 创建，apply 时从图状态绑定。 */
    private final AtomicReference<String> tokenUsageRunId = new AtomicReference<>("");

    public VerifierNode(ChatModel chatModel, TokenUsageService tokenUsage) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new MyLoggerAdvisor(),
                        new TokenUsageAdvisor("verifier", tokenUsage, tokenUsageRunId::get))
                .build();
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        tokenUsageRunId.set(state.value(GraphState.RUN_ID, ""));
        List<String> pendingConfirmations = state.value(GraphState.PENDING_CONFIRMATION)
                .map(value -> (List<String>) value)
                .orElse(List.of());
        if (!pendingConfirmations.isEmpty()) {
            // run 挂起等待工具确认：不做质检，避免对半成品结果给出 pass/revise
            return Map.of(GraphState.VERIFICATION_STATUS, "paused",
                    GraphState.VERIFICATION_FEEDBACK, "",
                    GraphState.REVISION, state.value(GraphState.REVISION, 0),
                    GraphState.ACTIVITIES, List.of("任务已暂停：等待用户确认工具执行"),
                    GraphState.SSE_EVENTS, List.of(ActivityEvent.of("verify", "质检员",
                            "任务已暂停：等待用户确认工具执行，确认后将继续执行本计划")));
        }
        String input = state.value(GraphState.INPUT, "");
        List<PlanStep> plan = state.value(GraphState.PLAN)
                .map(value -> (List<PlanStep>) value)
                .orElse(List.of());
        List<String> results = state.value(GraphState.TASK_RESULTS)
                .map(value -> (List<String>) value)
                .orElse(List.of());
        int previousRevisions = state.value(GraphState.REVISION, 0);
        if (previousRevisions >= MAX_REVISIONS) {
            // 返工后未进行第二次模型验收，不能将其标记为通过。
            return Map.of(GraphState.VERIFICATION_STATUS, "unverified",
                    GraphState.VERIFICATION_FEEDBACK, "",
                    GraphState.REVISION, previousRevisions,
                    GraphState.ACTIVITIES, List.of("已完成一轮返工，未进行二次验收，交付结果将标记为未验收"),
                    GraphState.SSE_EVENTS, List.of(ActivityEvent.of("verify", "质检员", "返工已完成，但未进行二次验收")));
        }
        String verdictJson;
        String activity;
        String status;
        String feedback;
        try {
            String planText = plan.stream()
                    .map(step -> "- [" + step.role().key() + "] " + step.title() + "：" + step.detail())
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            String resultsText = String.join("\n\n", results);
            verdictJson = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("用户请求：" + input + "\n\n计划：\n" + planText
                            + "\n\n各子任务结果：\n" + (resultsText.isBlank() ? "（无结果）" : resultsText))
                    .call()
                    .content();
            JsonNode verdict = MAPPER.readTree(extractJson(verdictJson));
            status = "revise".equalsIgnoreCase(verdict.path("status").asText("pass")) ? "revise" : "pass";
            feedback = verdict.path("feedback").asText("");
            activity = "pass".equals(status) ? "质检通过" : "质检要求返工：" + compact(feedback);
        } catch (Exception exception) {
            log.warn("verifier 调用失败，结果标记为未验收：{}", exception.getMessage());
            status = "unverified";
            feedback = "";
            activity = "质检器暂不可用，结果未验收";
        }
        int revision = "revise".equals(status) ? previousRevisions + 1 : previousRevisions;
        log.info(activity);
        return Map.of(GraphState.VERIFICATION_STATUS, status,
                GraphState.VERIFICATION_FEEDBACK, feedback,
                GraphState.REVISION, revision,
                GraphState.ACTIVITIES, List.of(activity),
                GraphState.SSE_EVENTS, List.of(ActivityEvent.of("verify", "质检员",
                        "pass".equals(status) ? "结果通过验收"
                                : "revise".equals(status) ? "要求返工：" + compact(feedback, 80)
                                : "质检不可用，结果未验收")));
    }

    /** 提取输出中第一个 JSON 对象片段；找不到时返回原文让 readTree 自行失败。 */
    static String extractJson(String text) {
        if (text == null) return "";
        String withoutFence = text.replaceAll("```(json|JSON)", "").replace("```", "");
        int start = withoutFence.indexOf('{');
        int end = withoutFence.lastIndexOf('}');
        if (start < 0 || end <= start) return withoutFence.trim();
        return withoutFence.substring(start, end + 1);
    }

    private static String compact(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }

    private static String compact(String value, int maxChars) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "...";
    }
}
