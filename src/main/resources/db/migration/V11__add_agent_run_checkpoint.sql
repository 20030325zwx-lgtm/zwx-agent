CREATE TABLE IF NOT EXISTS agent_run (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES agent_conversation(id) ON DELETE CASCADE,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    agent_key VARCHAR(32) NOT NULL,
    user_message_id BIGINT NOT NULL REFERENCES agent_chat_message(id) ON DELETE CASCADE,
    assistant_message_id BIGINT REFERENCES agent_chat_message(id) ON DELETE SET NULL,
    status VARCHAR(16) NOT NULL,
    input TEXT NOT NULL,
    history_context TEXT,
    knowledge_context TEXT,
    rag_trace_json TEXT,
    last_node VARCHAR(32),
    next_node VARCHAR(32),
    plan JSONB,
    task_results JSONB NOT NULL DEFAULT '[]'::jsonb,
    activities JSONB NOT NULL DEFAULT '[]'::jsonb,
    sse_events JSONB NOT NULL DEFAULT '[]'::jsonb,
    revision INTEGER NOT NULL DEFAULT 0,
    verification_status VARCHAR(16),
    verification_feedback TEXT,
    final_answer TEXT,
    partial_answer TEXT,
    error_message TEXT,
    resume_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_run_conversation
    ON agent_run (conversation_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_run_status
    ON agent_run (status, updated_at DESC);

COMMENT ON TABLE agent_run IS '超级智能体图执行实例与可恢复检查点';
COMMENT ON COLUMN agent_run.last_node IS '最近一个已完成的图节点';
COMMENT ON COLUMN agent_run.next_node IS '恢复时应继续执行的图节点';
