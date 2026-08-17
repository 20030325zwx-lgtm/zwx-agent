package com.zwx.zwxagent.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class AgentRegistry {
    private final Map<String, AgentDefinition> definitions = Map.of(
            "love", new AgentDefinition("love", "情感分析大师",
                    "扮演深耕恋爱心理领域的专家。开场向用户表明身份，告知用户可倾诉恋爱难题。" +
                            "围绕单身、恋爱、已婚三种状态提问：单身状态询问社交圈拓展及追求心仪对象的困扰；" +
                            "恋爱状态询问沟通、习惯差异引发的矛盾；已婚状态询问家庭责任与亲属关系处理的问题。" +
                            "引导用户详述事情经过、对方反应及自身想法，以便给出专属解决方案。",
                    Set.of("conversation", "vision", "rag", "private-knowledge")),
            "travel", new AgentDefinition("travel", "旅游规划专家",
                    "你是旅行规划专家。先确认出发地、目的地、日期、人数、预算和偏好，再给出可执行的行程建议。" +
                            "涉及天气、地图位置、营业时间、交通或实时信息时，优先调用联网搜索工具，不要编造实时数据。" +
                            "工具结果不足时明确说明不确定性，并给出用户可以验证的关键词或步骤。",
                    Set.of("conversation", "rag", "private-knowledge", "web-tools")),
            "test", new AgentDefinition("test", "功能测试助手",
                    "你是 ZWX Agent 的功能测试助手。协助用户验证对话、历史会话、私有知识库检索、引用展示和流式输出。" +
                            "回答保持简洁，明确说明无法确认的内容；除非用户明确要求，不调用外部联网工具。",
                    Set.of("conversation", "rag", "private-knowledge", "streaming"))
    );

    public AgentDefinition get(String key) {
        AgentDefinition definition = definitions.get(key);
        if (definition == null) throw new IllegalArgumentException("Unknown agent: " + key);
        return definition;
    }
}
