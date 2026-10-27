CREATE TABLE IF NOT EXISTS agent_run_workspace (
    run_id VARCHAR(64) PRIMARY KEY REFERENCES agent_run(id) ON DELETE CASCADE,
    project_id VARCHAR(64) NOT NULL REFERENCES agent_project(id) ON DELETE RESTRICT,
    mode VARCHAR(16) NOT NULL,
    checkout_path VARCHAR(2048) NOT NULL,
    branch_name VARCHAR(255),
    base_head VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_run_workspace_mode CHECK (mode IN ('DIRECT', 'WORKTREE')),
    CONSTRAINT ck_agent_run_workspace_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'INTERRUPTED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_agent_run_workspace_project
    ON agent_run_workspace (project_id, created_at DESC);

COMMENT ON TABLE agent_run_workspace IS 'Run 固化的源码 checkout；Git 项目默认使用受管 worktree，恢复时复用';
