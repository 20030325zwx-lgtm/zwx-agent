package com.zwx.zwxagent.agent.graph;

/**
 * 面向用户的语义化执行事件：随 SSE activity 推送，前端直接渲染。
 *
 * @param phase   plan | work | verify
 * @param agent   展示用角色名（规划器/调研员/撰写员/分析员/通用助理/质检员）
 * @param summary 一句话中文摘要
 */
public record ActivityEvent(String phase, String agent, String summary) {

    public static ActivityEvent of(String phase, String agent, String summary) {
        return new ActivityEvent(phase, agent, summary == null ? "" : summary);
    }
}
