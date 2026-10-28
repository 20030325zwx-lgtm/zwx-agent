package com.zwx.zwxagent.agent.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.zwx.zwxagent.agent.advisor.MyLoggerAdvisor;
import com.zwx.zwxagent.agent.advisor.TokenUsageAdvisor;
import com.zwx.zwxagent.agent.observability.TokenUsageService;
import com.zwx.zwxagent.platform.intent.WorkspaceRequestDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 规划节点：把用户请求拆解为至多 {@link PlanJsonParser#MAX_STEPS} 个子任务，
 * 每个子任务指派一个工作者角色；解析失败时回退为单个 general 子任务。
 */
@Slf4j
public class PlannerNode implements NodeAction {

    private static final String SYSTEM_PROMPT = """
            你是多智能体协作系统的规划器。把用户请求拆解为 1 到 4 个子任务。
            每个子任务必须指定执行角色，角色定义：
            - researcher：联网调研员（搜索、抓取网页、下载公开资料）
            - author：文档撰写员（读写文件、生成 PDF 交付物）
            - analyst：数据分析员（只读 SQL 查询、白名单命令分析数据）
            - coder：代码工程师（浏览、修改、构建检查并提交当前项目源码）
            - general：通用助理（可用全部工具）
            规则：
            1. 简单请求只拆 1 个子任务，不要过度拆分。
            2. 后续步骤需要用到前面步骤的产出时（如先调研、再分析、最后写报告），必须用 depends_on 标注上游步骤 id；
               没有依赖关系的子任务会并行执行，不要给相互独立的步骤添加依赖。
            3. detail 写清要做什么、产出什么；acceptance 用一句可验证的中文写明这条子任务怎样才算完成
               （如"产出包含三家竞品价格对比的报告文件"），写不出来就留空，不要编造。
            4. 只输出 JSON 数组，不要输出任何其他文字。
            输出格式：
            [{"id":"s1","title":"子任务标题","role":"researcher","detail":"具体要做什么、产出什么","acceptance":"完成判据","depends_on":[]}]
            """;

    private final ChatClient chatClient;

    /** 计量归属的 run：节点实例按 run 创建，apply 时从图状态绑定。 */
    private final AtomicReference<String> tokenUsageRunId = new AtomicReference<>("");

    public PlannerNode(ChatModel chatModel, TokenUsageService tokenUsage) {
        this(chatModel, tokenUsage, null);
    }

    public PlannerNode(ChatModel chatModel, TokenUsageService tokenUsage,
                 com.zwx.zwxagent.agent.observability.ModelIoService modelIo) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new MyLoggerAdvisor(),
                        new TokenUsageAdvisor("planner", tokenUsage, tokenUsageRunId::get, modelIo))
                .build();
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        tokenUsageRunId.set(state.value(GraphState.RUN_ID, ""));
        String input = state.value(GraphState.INPUT, "");
        RunContext runContext = state.value(GraphState.RUN_CONTEXT, RunContext.class).orElse(null);
        String knowledge = runContext == null ? "" : runContext.knowledgeContext();
        boolean webSearchEnabled = runContext == null || runContext.webSearchEnabled();
        boolean projectToolsEnabled = runContext != null && hasTool(runContext.tools(), "listProjectTree");
        String history = state.value(GraphState.HISTORY, "");
        if (!knowledge.isBlank()) {
            List<PlanStep> plan = List.of(new PlanStep("s1", "依据私有知识回答", WorkerRole.GENERAL,
                    "用户请求：" + input + "。仅依据本轮检索到的私有知识片段直接作答；不要联网或用工具寻找替代资料。"
                            + "关键结论引用 [文件名#切片号]；资料未明确支持的内容说明无法确认。", List.of()));
            String activity = "检索到私有知识，交由单个通用工作者依据资料回答";
            log.info(activity);
            return Map.of(GraphState.PLAN, plan,
                    GraphState.ACTIVITIES, List.of(activity),
                    GraphState.SSE_EVENTS, List.of(ActivityEvent.of("plan", "规划器", activity)));
        }
        if (projectToolsEnabled && WorkspaceRequestDetector.referencesWorkspace(input)) {
            List<PlanStep> plan = List.of(new PlanStep("s1", "检查当前工作区并回答", WorkerRole.CODER,
                    "用户请求：" + input + "。优先检查绑定工作区中的本地目录和源码。"
                            + "若请求中包含目录或项目名称，先用该名称作为精确相对路径调用 listProjectTree，"
                            + "再读取 README、构建配置和相关源码；不要在完成本地检查前联网猜测同名项目。", List.of()));
            String activity = "请求明确指向当前工作区，交由代码工程师优先检查本地项目";
            return Map.of(GraphState.PLAN, plan,
                    GraphState.ACTIVITIES, List.of(activity),
                    GraphState.SSE_EVENTS, List.of(ActivityEvent.of("plan", "规划器", activity)));
        }
        String prompt = (history.isBlank() ? "" : "最近对话摘要：\n" + history + "\n\n")
                + "用户请求：" + input
                + (webSearchEnabled ? "" : "\n\n注意：本轮未开启联网搜索，researcher 角色不可用；"
                + "不要规划搜索、抓取网页或下载网络资源。需要实时外部信息时，使用 general 并说明无法联网获取。")
                + (projectToolsEnabled
                ? "\n\n当前对话已绑定可访问的工作区。请求涉及当前项目、目录、代码或仓库内名称时，优先使用 coder 检查本地内容，不能先联网猜测同名项目。"
                : "\n\n注意：当前对话没有绑定可访问的项目，coder 角色不可用；不要规划源码浏览或 Git 检查。");
        List<PlanStep> plan;
        String activity;
        try {
            String output = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
                    .call()
                    .content();
            plan = PlanJsonParser.parse(output, input);
            activity = "规划完成：" + plan.stream()
                    .map(step -> step.role().key() + " ← " + step.title())
                    .reduce((a, b) -> a + "；" + b)
                    .orElse("");
        } catch (Exception exception) {
            log.warn("planner 调用失败，回退为单个 general 子任务：{}", exception.getMessage());
            plan = PlanJsonParser.parse(null, input);
            activity = "规划器暂不可用，已按通用模式直接执行";
        }
        log.info(activity);
        return Map.of(GraphState.PLAN, plan,
                GraphState.ACTIVITIES, List.of(activity),
                GraphState.SSE_EVENTS, List.of(ActivityEvent.of("plan", "规划器",
                        plan.size() == 1 ? "任务较简单，由 " + plan.get(0).role().displayName() + " 直接完成"
                                : "拆解为 " + plan.size() + " 个子任务（" + dependencySummary(plan) + "）：" + plan.stream()
                                .map(step -> step.role().displayName() + "←" + step.title())
                                .reduce((a, b) -> a + "；" + b).orElse(""))));
    }

    /** 依赖概览：全部无依赖时并行，否则说明波次执行。 */
    private static String dependencySummary(List<PlanStep> plan) {
        long withDeps = plan.stream().filter(step -> !step.dependsOn().isEmpty()).count();
        return withDeps == 0 ? "并行执行" : withDeps + " 步有前置依赖，按依赖顺序执行，其余并行";
    }

    private static boolean hasTool(org.springframework.ai.tool.ToolCallback[] tools, String name) {
        if (tools == null) return false;
        for (org.springframework.ai.tool.ToolCallback tool : tools) {
            if (tool != null && tool.getToolDefinition() != null && name.equals(tool.getToolDefinition().name())) return true;
        }
        return false;
    }
}
