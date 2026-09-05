CREATE TABLE IF NOT EXISTS app_user (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_user_username UNIQUE (username)
);

CREATE INDEX IF NOT EXISTS idx_app_user_tenant ON app_user (tenant_id);

ALTER TABLE love_conversation ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE love_conversation ADD COLUMN IF NOT EXISTS user_id VARCHAR(64);
UPDATE love_conversation SET user_id = 'legacy' WHERE user_id IS NULL;
ALTER TABLE love_conversation ALTER COLUMN user_id SET DEFAULT 'legacy';
ALTER TABLE love_conversation ALTER COLUMN user_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_love_conversation_owner_updated
    ON love_conversation (tenant_id, user_id, updated_at DESC);

ALTER TABLE agent_conversation ADD COLUMN IF NOT EXISTS user_id VARCHAR(64);
UPDATE agent_conversation SET user_id = 'legacy' WHERE user_id IS NULL;
ALTER TABLE agent_conversation ALTER COLUMN user_id SET DEFAULT 'legacy';
ALTER TABLE agent_conversation ALTER COLUMN user_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_conversation_owner_updated
    ON agent_conversation (tenant_id, user_id, agent_key, updated_at DESC);
