package com.zwx.zwxagent.agent;

/**
 * 一次 agent run 的归属身份，由创建方在 run 前设置；用于 hook（如执行轨迹）定位数据。
 * 缺省为 null（程序内调用、单测），相关 hook 自动跳过。
 */
public record AgentRunIdentity(String tenantId, String agentKey, String conversationId) {

    public AgentRunIdentity {
        if (tenantId == null || tenantId.isBlank() || agentKey == null || agentKey.isBlank()) {
            throw new IllegalArgumentException("tenantId and agentKey are required");
        }
    }

    /** conversationId 可空（非会话型调用）。 */
    public boolean traceable() {
        return conversationId != null && !conversationId.isBlank();
    }
}
