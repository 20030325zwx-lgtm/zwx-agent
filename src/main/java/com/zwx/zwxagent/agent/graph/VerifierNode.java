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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 质检节点：先做结构化证据门禁（失败/空结果、承诺产出文件却没有文件名），再对照用户请求与计划
 * 做模型评价；结论为 pass（通过）或 revise（返工 + 反馈 + 点名步骤）。
 * 证据门禁不通过直接返工，不消耗模型评价；质检失败按未验收处理，避免死循环。
 */
@Slf4j
public class VerifierNode implements NodeAction {

    static final int MAX_REVISIONS = 1;

    static final String SYSTEM_PROMPT = """
            你是多智能体系统的质检员。对照用户请求、各子任务的完成判据（acceptance）与结果，核对交付质量：
            1. 每个子任务是否达到其完成判据；未写判据的，结果是否覆盖了该步骤要做的核心内容；
            2. 结果之间是否矛盾、是否与用户请求无关；
            3. 是否存在明显未完成的关键项。
            只输出 JSON，不要输出其他文字：
            {"status":"pass","feedback":""}
            或
            {"status":"revise","steps":[0,2],"feedback":"一句中文，说明缺什么、错什么、如何修正"}
            steps 是需要返工的子任务序号（从 0 开始，对计划顺序）；省略或为空表示全部重做。
            注意：轻微瑕疵直接放行；只有关键内容缺失、结果跑题或未达到完成判据才要求返工。
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 结果文本中出现的文件名形态（用于核对"承诺产出文件"类步骤）。 */
    private static final Pattern FILENAME_PATTERN =
            Pattern.compile("[\\w\\u4e00-\\u9fa5.-]+\\.(pdf|PDF|md|MD|txt|docx|xlsx|csv|html|json|png|jpg|jpeg)");

    private final ChatClient chatClient;

    /** 计量归属的 run：节点实例按 run 创建，apply 时从图状态绑定。 */
    private final AtomicReference<String> tokenUsageRunId = new AtomicReference<>("");

    public VerifierNode(ChatModel chatModel, TokenUsageService tokenUsage) {
        this(chatModel, tokenUsage, null);
    }

    public VerifierNode(ChatModel chatModel, TokenUsageService tokenUsage,
                 com.zwx.zwxagent.agent.observability.ModelIoService modelIo) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new MyLoggerAdvisor(),
                        new TokenUsageAdvisor("verifier", tokenUsage, tokenUsageRunId::get, modelIo))
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
                    GraphState.REVISE_STEPS, List.of(),
                    GraphState.REVISION, state.value(GraphState.REVISION, 0),
                    GraphState.ACTIVITIES, List.of("任务已暂停：等待用户确认工具执行"),
                    GraphState.SSE_EVENTS, List.of(ActivityEvent.of("verify", "质检员",
                            "任务已暂停：等待用户确认工具执行，确认后将继续执行本计划")));
        }
        String input = state.value(GraphState.INPUT, "");
        List<PlanStep> plan = state.value(GraphState.PLAN)
                .map(value -> (List<PlanStep>) value)
                .orElse(List.of());
        List<String> accumulated = state.value(GraphState.TASK_RESULTS)
                .map(value -> (List<String>) value)
                .orElse(List.of());
        // 返工轮会追加整轮结果：与计划对齐取最后一轮，避免旧轮结果干扰证据核对
        List<String> results = accumulated.size() > plan.size()
                ? List.copyOf(accumulated.subList(accumulated.size() - plan.size(), accumulated.size()))
                : accumulated;
        int previousRevisions = state.value(GraphState.REVISION, 0);
        if (previousRevisions >= MAX_REVISIONS) {
            // 返工后未进行第二次模型验收，不能将其标记为通过。
            return Map.of(GraphState.VERIFICATION_STATUS, "unverified",
                    GraphState.VERIFICATION_FEEDBACK, "",
                    GraphState.REVISE_STEPS, List.of(),
                    GraphState.REVISION, previousRevisions,
                    GraphState.ACTIVITIES, List.of("已完成一轮返工，未进行二次验收，交付结果将标记为未验收"),
                    GraphState.SSE_EVENTS, List.of(ActivityEvent.of("verify", "质检员", "返工已完成，但未进行二次验收")));
        }
        // 结构化证据门禁：硬性缺口直接返工并点名步骤，不进模型评价
        List<String> gaps = evidenceGaps(plan, results);
        if (!gaps.isEmpty()) {
            List<Integer> targets = gapSteps(plan, results);
            String feedback = String.join("；", gaps);
            log.info("质检证据门禁要求返工：{}", feedback);
            return Map.of(GraphState.VERIFICATION_STATUS, "revise",
                    GraphState.VERIFICATION_FEEDBACK, feedback,
                    GraphState.REVISE_STEPS, targets,
                    GraphState.REVISION, previousRevisions + 1,
                    GraphState.ACTIVITIES, List.of("质检要求返工：" + compact(feedback)),
                    GraphState.SSE_EVENTS, List.of(ActivityEvent.of("verify", "质检员",
                            "证据核对未通过，返工 " + targets.size() + " 步：" + compact(feedback, 80))));
        }
        String verdictJson;
        String activity;
        String status;
        String feedback;
        List<Integer> targets = List.of();
        try {
            String planText = planDetails(plan, results);
            verdictJson = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("用户请求：" + input + "\n\n计划与结果：\n" + planText)
                    .call()
                    .content();
            JsonNode verdict = MAPPER.readTree(extractJson(verdictJson));
            status = "revise".equalsIgnoreCase(verdict.path("status").asText("pass")) ? "revise" : "pass";
            feedback = verdict.path("feedback").asText("");
            targets = parseStepTargets(verdict.path("steps"), plan.size());
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
                GraphState.REVISE_STEPS, "revise".equals(status) ? targets : List.of(),
                GraphState.REVISION, revision,
                GraphState.ACTIVITIES, List.of(activity),
                GraphState.SSE_EVENTS, List.of(ActivityEvent.of("verify", "质检员",
                        "pass".equals(status) ? "结果通过验收"
                                : "revise".equals(status) ? "要求返工：" + compact(feedback, 80)
                                : "质检不可用，结果未验收")));
    }

    /** 计划与结果逐条对齐的核对文本：含完成判据与结果摘要。 */
    private static String planDetails(List<PlanStep> plan, List<String> results) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < plan.size(); index++) {
            PlanStep step = plan.get(index);
            String result = index < results.size() ? results.get(index) : "";
            builder.append("- 第").append(index).append("步 [").append(step.role().key()).append("] ")
                    .append(step.title()).append("：").append(step.detail()).append('\n');
            if (!step.acceptance().isBlank()) builder.append("  完成判据：").append(step.acceptance()).append('\n');
            if (!step.dependsOn().isEmpty()) builder.append("  依赖：").append(String.join("、", step.dependsOn())).append('\n');
            builder.append("  结果：").append(result.isBlank() ? "（无结果）" : compact(result, 600)).append('\n');
        }
        return builder.toString();
    }

    /**
     * 结构化证据门禁：返回硬性缺口描述（空表示通过）。
     * 覆盖：结果为空、执行失败/被跳过、步骤要求产出文件但结果里没有任何文件名。
     */
    static List<String> evidenceGaps(List<PlanStep> plan, List<String> results) {
        List<String> gaps = new ArrayList<>();
        int size = Math.min(plan.size(), results.size());
        for (int index = 0; index < size; index++) {
            PlanStep step = plan.get(index);
            String result = results.get(index) == null ? "" : results.get(index);
            String label = "第" + (index + 1) + "步「" + step.title() + "」";
            if (result.isBlank()) {
                gaps.add(label + "没有产出结果");
                continue;
            }
            if (result.startsWith("子任务失败") || result.contains("上游任务失败或被跳过")
                    || result.contains("执行队列已满，未能启动") || result.endsWith("已取消")) {
                gaps.add(label + "执行失败或被跳过");
                continue;
            }
            if (promisesFile(step) && !FILENAME_PATTERN.matcher(result).find()) {
                gaps.add(label + "要求产出文件，但结果未包含任何文件名");
            }
        }
        return gaps;
    }

    /** 证据门禁点名的返工步骤序号（0 起）。 */
    static List<Integer> gapSteps(List<PlanStep> plan, List<String> results) {
        List<Integer> targets = new ArrayList<>();
        List<String> gaps = evidenceGaps(plan, results);
        int size = Math.min(plan.size(), results.size());
        for (int index = 0; index < size; index++) {
            PlanStep step = plan.get(index);
            String result = results.get(index) == null ? "" : results.get(index);
            String label = "第" + (index + 1) + "步「" + step.title() + "」";
            boolean hardFailure = result.isBlank() || result.startsWith("子任务失败")
                    || result.contains("上游任务失败或被跳过")
                    || result.contains("执行队列已满，未能启动") || result.endsWith("已取消");
            boolean missingFile = promisesFile(step) && !hardFailure && !FILENAME_PATTERN.matcher(result).find();
            boolean named = gaps.stream().anyMatch(gap -> gap.startsWith(label));
            if ((hardFailure || missingFile) && named) targets.add(index);
        }
        return targets;
    }

    /** 步骤是否承诺产出文件：author 角色或 detail/acceptance 明确要求生成文件类交付物。 */
    private static boolean promisesFile(PlanStep step) {
        if (step.role() == WorkerRole.AUTHOR) return true;
        return (step.detail() + step.acceptance()).matches(".*(生成|输出|导出|保存).*(文件|PDF|表格|文档).*");
    }

    /** 解析质检点名的返工步骤：越界/非法值丢弃；空表示全部重做。 */
    static List<Integer> parseStepTargets(JsonNode node, int planSize) {
        if (!node.isArray()) return List.of();
        List<Integer> targets = new ArrayList<>();
        node.forEach(item -> {
            if (!item.canConvertToInt()) return;
            int value = item.asInt(-1);
            if (value >= 0 && value < planSize && !targets.contains(value)) targets.add(value);
        });
        return List.copyOf(targets);
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
