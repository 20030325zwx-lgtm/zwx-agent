CREATE TABLE IF NOT EXISTS agent_project_write_lock (
    project_id VARCHAR(64) PRIMARY KEY REFERENCES agent_project(id) ON DELETE CASCADE,
    run_id VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_project_write_lock_expiry
    ON agent_project_write_lock (expires_at);

COMMENT ON TABLE agent_project_write_lock IS 'DIRECT 模式项目单写者租约，避免并发 run 修改同一源码目录';
