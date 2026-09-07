package com.zwx.zwxagent.agent.hook;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次 agent run 的上下文：随 run 创建销毁，hook 之间通过它传递数据。
 * hook 不得持有 per-run 可变状态，单例 hook 服务多并发 run。
 */
public final class AgentRunContext {

    private final String runId;
    private final String agentKey;
    private final String tenantId;
    private final String conversationId;
    private final String agentName;
    private final String userPrompt;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private volatile int currentStep;

    private AgentRunContext(Builder builder) {
        this.runId = builder.runId;
        this.agentKey = builder.agentKey;
        this.tenantId = builder.tenantId;
        this.conversationId = builder.conversationId;
        this.agentName = builder.agentName;
        this.userPrompt = builder.userPrompt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String runId() {
        return runId;
    }

    public String agentKey() {
        return agentKey;
    }

    public String tenantId() {
        return tenantId;
    }

    public String conversationId() {
        return conversationId;
    }

    public String agentName() {
        return agentName;
    }

    public String userPrompt() {
        return userPrompt;
    }

    public int currentStep() {
        return currentStep;
    }

    public void currentStep(int stepNumber) {
        this.currentStep = stepNumber;
    }

    /** 同一 run 内 hook 间协作的属性袋。 */
    public Map<String, Object> attributes() {
        return attributes;
    }

    public static final class Builder {
        private String runId = UUID.randomUUID().toString();
        private String agentKey;
        private String tenantId;
        private String conversationId;
        private String agentName;
        private String userPrompt;

        public Builder runId(String value) {
            this.runId = value;
            return this;
        }

        public Builder agentKey(String value) {
            this.agentKey = value;
            return this;
        }

        public Builder tenantId(String value) {
            this.tenantId = value;
            return this;
        }

        public Builder conversationId(String value) {
            this.conversationId = value;
            return this;
        }

        public Builder agentName(String value) {
            this.agentName = value;
            return this;
        }

        public Builder userPrompt(String value) {
            this.userPrompt = value;
            return this;
        }

        public AgentRunContext build() {
            return new AgentRunContext(this);
        }
    }
}
