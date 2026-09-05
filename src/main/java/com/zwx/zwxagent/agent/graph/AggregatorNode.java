package com.zwx.zwxagent.agent.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.zwx.zwxagent.advisor.MyLoggerAdvisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

/**
 * 交付节点：汇总各子任务结果，产出与原 manus 契约一致的中文交付总结。
 */
@Slf4j
public class AggregatorNode implements NodeAction {

    private static final String SYSTEM_PROMPT = """
            你是多智能体系统的交付负责人。基于各子任务结果，为用户撰写最终交付总结，要求：
            1. 先直接给出针对用户请求的结论或答案；
            2. 再简要说明团队完成了哪些工作；
            3. 列出重要结果、数据或限制；
            4. 用中文，保持精炼，不编造未在结果中出现的信息。
            严格禁止：如果各子任务结果中没有明确提到已生成文件（PDF 等），
            绝对不要提及"已生成文件/可下载"之类的表述，也不要输出任何下载链接或文件名。
            """;

    private final ChatClient chatClient;

    public AggregatorNode(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String input = state.value(GraphState.INPUT, "");
        List<String> results = state.value(GraphState.TASK_RESULTS)
                .map(value -> (List<String>) value)
                .orElse(List.of());
        String finalAnswer;
        try {
            finalAnswer = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("用户请求：" + input
                            + "\n\n各子任务结果（含工作记录与结论）：\n" + String.join("\n\n", results))
                    .call()
                    .content();
            if (finalAnswer == null || finalAnswer.isBlank()) {
                throw new IllegalStateException("空回答");
            }
        } catch (Exception exception) {
            log.warn("aggregator 调用失败，回退为结果拼接：{}", exception.getMessage());
            finalAnswer = "本次任务的执行结果：\n\n" + String.join("\n\n", results);
        }
        return Map.of(GraphState.FINAL_ANSWER, finalAnswer);
    }
}
