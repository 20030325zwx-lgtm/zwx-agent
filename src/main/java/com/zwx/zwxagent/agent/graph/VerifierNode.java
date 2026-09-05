package com.zwx.zwxagent.agent.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.zwx.zwxagent.advisor.MyLoggerAdvisor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

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

    public VerifierNode(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String input = state.value(GraphState.INPUT, "");
        List<PlanStep> plan = state.value(GraphState.PLAN)
                .map(value -> (List<PlanStep>) value)
                .orElse(List.of());
        List<String> results = state.value(GraphState.TASK_RESULTS)
                .map(value -> (List<String>) value)
                .orElse(List.of());
        int previousRevisions = state.value(GraphState.REVISION, 0);
        if (previousRevisions >= MAX_REVISIONS) {
            // 已返工过一轮，不再消耗质检调用，直接放行进入交付。
            return Map.of(GraphState.VERIFICATION_STATUS, "pass",
                    GraphState.VERIFICATION_FEEDBACK, "",
                    GraphState.REVISION, previousRevisions,
                    GraphState.ACTIVITIES, List.of("已完成一轮返工，直接进入交付"));
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
            log.warn("verifier 调用失败，按通过处理：{}", exception.getMessage());
            status = "pass";
            feedback = "";
            activity = "质检器暂不可用，按通过处理";
        }
        int revision = "revise".equals(status) ? previousRevisions + 1 : previousRevisions;
        log.info(activity);
        return Map.of(GraphState.VERIFICATION_STATUS, status,
                GraphState.VERIFICATION_FEEDBACK, feedback,
                GraphState.REVISION, revision,
                GraphState.ACTIVITIES, List.of(activity),
                GraphState.SSE_EVENTS, List.of(ActivityEvent.of("verify", "质检员",
                        "pass".equals(status) ? "结果通过验收"
                                : "要求返工：" + compact(feedback, 80))));
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
