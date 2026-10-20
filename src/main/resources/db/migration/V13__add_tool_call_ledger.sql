CREATE TABLE IF NOT EXISTS agent_tool_call (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    tool_name VARCHAR(128) NOT NULL,
    arguments_hash VARCHAR(64) NOT NULL,
    arguments TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    result TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, tool_name, arguments_hash)
);

CREATE INDEX IF NOT EXISTS idx_agent_tool_call_run
    ON agent_tool_call (run_id, id);

COMMENT ON TABLE agent_tool_call IS '超级智能体工具调用幂等账本；恢复时复用成功结果或阻止未知副作用重放';
COMMENT ON COLUMN agent_tool_call.status IS 'PENDING | SUCCEEDED | FAILED | UNKNOWN';
