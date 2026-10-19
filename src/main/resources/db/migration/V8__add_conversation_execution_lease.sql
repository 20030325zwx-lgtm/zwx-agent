CREATE TABLE IF NOT EXISTS agent_conversation_lock (
    conversation_id VARCHAR(160) PRIMARY KEY,
    owner_token VARCHAR(36) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_conversation_lock_expiry
    ON agent_conversation_lock (expires_at);

COMMENT ON TABLE agent_conversation_lock IS '会话级 Agent 执行租约锁，支持多实例互斥并允许过期回收';
