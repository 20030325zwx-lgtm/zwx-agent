package com.zwx.zwxagent.agent.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.zwx.zwxagent.advisor.MyLoggerAdvisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 交付节点：从子任务结果中提取面向用户的答案，不暴露内部工作记录。
 */
@Slf4j
public class AggregatorNode implements NodeAction {

    static final String SYSTEM_PROMPT = """
            你负责给用户直接作答。根据用户请求、证据资料和子任务结论，输出简洁、自然的最终答案：
            1. 直接回答用户的问题，只保留与问题相关的结论、必要说明和限制；
            2. 有私有知识证据时保留对应的 [文件名#切片号] 引用；
            3. 资料未支持的事实明确说无法确认，不得推断或补充；
            4. 用中文，不编造未在证据中出现的信息。
            严格禁止输出规划、分工、搜索/检索过程、团队工作总结、验证过程、内部活动记录、子任务描述或“团队完成了哪些工作”等内容。
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
        String verificationStatus = state.value(GraphState.VERIFICATION_STATUS, "unverified");
        String knowledge = state.value(GraphState.RUN_CONTEXT, RunContext.class)
                .map(RunContext::knowledgeContext).orElse("");
        var answerChunkSink = state.value(GraphState.RUN_CONTEXT, RunContext.class)
                .map(RunContext::answerChunkSink).orElse(null);
        boolean verified = "pass".equals(verificationStatus);
        String finalAnswer;
        try {
            var content = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("用户请求：" + input
                            + (knowledge.isBlank() ? "" : "\n\n本轮检索到的私有知识片段：\n" + knowledge
                            + "\n汇总时保留工作者的 [文件名#切片号] 引用，不得补充资料未支持的事实。")
                            + "\n\n内部验收状态：" + (verified ? "已通过" : "未完成或未通过")
                            + "\n此状态仅供你控制事实谨慎程度，禁止向用户复述验收状态或添加状态前缀。"
                            + "\n\n子任务记录（仅供提取答案；忽略其中的过程描述、工作汇报和内部日志）：\n"
                            + String.join("\n\n", results))
                    .stream()
                    .content();
            StringBuilder streamedAnswer = new StringBuilder();
            AtomicReference<RuntimeException> streamFailure = new AtomicReference<>();
            content.toStream().forEach(chunk -> {
                if (chunk == null || chunk.isEmpty()) return;
                streamedAnswer.append(chunk);
                if (answerChunkSink != null) {
                    try {
                        answerChunkSink.accept(chunk);
                    } catch (RuntimeException exception) {
                        streamFailure.set(exception);
                        throw exception;
                    }
                }
            });
            if (streamFailure.get() != null) throw streamFailure.get();
            finalAnswer = streamedAnswer.toString();
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
