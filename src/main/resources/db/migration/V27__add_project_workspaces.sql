CREATE TABLE IF NOT EXISTS agent_project (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    name VARCHAR(80) NOT NULL,
    directory_name VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_project_owner_directory UNIQUE (tenant_id, owner_user_id, directory_name)
);

CREATE INDEX IF NOT EXISTS idx_agent_project_owner_updated
    ON agent_project (tenant_id, owner_user_id, updated_at DESC);

ALTER TABLE agent_conversation
    ADD COLUMN IF NOT EXISTS project_id VARCHAR(64) REFERENCES agent_project(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_agent_conversation_project_updated
    ON agent_conversation (project_id, updated_at DESC);
