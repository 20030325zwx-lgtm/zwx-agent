-- 长期记忆事实日志（design/plans/2026-09-08-memory-skills-workspace.md §3.3 批次 C）
-- L3 分层记忆：跨会话事实，按 (tenant, agent, fact_hash) 去重，冲突更新 last_seen_at。

CREATE TABLE IF NOT EXISTS agent_memory_fact (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    agent_key VARCHAR(32) NOT NULL,
    user_id VARCHAR(64),
    fact TEXT NOT NULL,
    fact_hash CHAR(64) NOT NULL,
    source_conversation_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_memory_fact ON agent_memory_fact (tenant_id, agent_key, fact_hash);
CREATE INDEX IF NOT EXISTS idx_agent_memory_scope ON agent_memory_fact (tenant_id, agent_key, created_at DESC);
